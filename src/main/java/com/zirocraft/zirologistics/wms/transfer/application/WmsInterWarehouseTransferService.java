package com.zirocraft.zirologistics.wms.transfer.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Tenant-scoped inter-warehouse transfer. Transit is represented by transfer quantities,
 * not by an inventory location. All on-hand and serial lifecycle writes go through
 * WmsInventoryPostingService.
 */
@Service
@Profile("wms")
public class WmsInterWarehouseTransferService {
    private final WmsMembershipAccessService accessService;
    private final WmsInventoryPostingService postingService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsInterWarehouseTransferService(WmsMembershipAccessService accessService,
                                            WmsInventoryPostingService postingService,
                                            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.postingService = Objects.requireNonNull(postingService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> list(WmsAuthenticatedPrincipal principal) {
        Access a = access(principal, "WMS_TRANSFER_READ");
        return jdbc.queryForList("""
                SELECT t.public_id,t.document_number,t.external_reference,t.transfer_type,t.status,
                       sw.public_id source_warehouse_public_id,sw.warehouse_code source_warehouse_code,
                       dw.public_id destination_warehouse_public_id,dw.warehouse_code destination_warehouse_code,
                       t.requested_at,t.scheduled_at,t.dispatched_at,t.received_at,t.posted_at,
                       t.notes,t.version,t.created_at,t.updated_at
                FROM stock_transfers t
                JOIN warehouses sw ON sw.id=t.source_warehouse_id AND sw.tenant_id=t.tenant_id
                JOIN warehouses dw ON dw.id=t.destination_warehouse_id AND dw.tenant_id=t.tenant_id
                WHERE t.tenant_id=? ORDER BY t.created_at DESC,t.id DESC
                """, a.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String,Object> get(WmsAuthenticatedPrincipal principal, String transferPublicId) {
        Access a = access(principal, "WMS_TRANSFER_READ");
        return view(a.tenantId(), transferPublicId);
    }

    private Map<String,Object> view(long tenantId, String transferPublicId) {
        Map<String,Object> h = header(tenantId, transferPublicId, false);
        Map<String,Object> result = new LinkedHashMap<>(h);
        long transferId = number(h,"id");
        List<Map<String,Object>> lines = jdbc.queryForList("""
                SELECT l.id,l.public_id,l.line_number,i.public_id item_public_id,i.sku,i.item_name,
                       u.uom_code,l.requested_qty,l.dispatched_qty,l.received_qty,l.damaged_qty,l.short_qty,
                       l.status,l.notes
                FROM stock_transfer_lines l
                JOIN items i ON i.id=l.item_id AND i.tenant_id=l.tenant_id
                JOIN units_of_measure u ON u.id=l.uom_id
                WHERE l.tenant_id=? AND l.stock_transfer_id=? ORDER BY l.line_number
                """,tenantId,transferId);
        for(Map<String,Object> line:lines){
            long lineId=number(line,"id");
            List<Map<String,Object>> allocations=jdbc.queryForList("""
                    SELECT a.public_id,a.planned_qty,a.dispatched_qty,a.received_qty,a.damaged_qty,a.short_qty,a.status,
                           sl.public_id source_location_public_id,sl.location_code source_location_code,
                           dl.public_id destination_location_public_id,dl.location_code destination_location_code,
                           lot.public_id lot_public_id,lot.lot_number
                    FROM stock_transfer_allocations a
                    JOIN warehouse_locations sl ON sl.id=a.source_location_id AND sl.warehouse_id=a.source_warehouse_id AND sl.tenant_id=a.tenant_id
                    JOIN warehouse_locations dl ON dl.id=a.destination_location_id AND dl.warehouse_id=a.destination_warehouse_id AND dl.tenant_id=a.tenant_id
                    LEFT JOIN inventory_lots lot ON lot.id=a.lot_id AND lot.tenant_id=a.tenant_id
                    WHERE a.tenant_id=? AND a.stock_transfer_line_id=? ORDER BY a.id
                    """,tenantId,lineId);
            for(Map<String,Object> allocation:allocations){
                allocation.put("serials",jdbc.queryForList("""
                        SELECT s.public_id serial_public_id,s.serial_number,ts.status,ts.dispatched_at,ts.received_at
                        FROM stock_transfer_line_serials ts
                        JOIN inventory_serials s ON s.id=ts.serial_id AND s.tenant_id=ts.tenant_id
                        JOIN stock_transfer_allocations a ON a.id=ts.stock_transfer_allocation_id AND a.tenant_id=ts.tenant_id
                        WHERE ts.tenant_id=? AND a.public_id=? ORDER BY s.serial_number
                        """,tenantId,allocation.get("public_id")));
            }
            line.remove("id");
            line.put("allocations",allocations);
        }
        result.remove("id");
        result.put("lines",lines);
        return result;
    }

    @Transactional
    public Map<String,Object> create(WmsAuthenticatedPrincipal principal, TransferCommand command) {
        Objects.requireNonNull(command,"Transfer request is required");
        Objects.requireNonNull(command.requestId(),"Request ID is required");
        lockTenant(principal);
        Access a=access(principal,"WMS_TRANSFER_CREATE");
        if(command.lines()==null || command.lines().isEmpty() || command.lines().size()>200)
            throw new IllegalArgumentException("Transfer must contain 1 to 200 lines");
        String document=required(command.documentNumber(),"document number",60).toUpperCase(Locale.ROOT);
        String type=enumValue(command.transferType(),Set.of("STANDARD","REPLENISHMENT","RETURN","OTHER"),"transfer type");
        Map<String,Object> source=requiredRow("SELECT id,status FROM warehouses WHERE tenant_id=? AND public_id=?",
                a.tenantId(),required(command.sourceWarehousePublicId(),"source warehouse public ID"),"Source warehouse not found");
        Map<String,Object> destination=requiredRow("SELECT id,status FROM warehouses WHERE tenant_id=? AND public_id=?",
                a.tenantId(),required(command.destinationWarehousePublicId(),"destination warehouse public ID"),"Destination warehouse not found");
        if(number(source,"id")==number(destination,"id"))throw new IllegalArgumentException("Source and destination warehouses must differ");
        if(!"ACTIVE".equals(source.get("status"))||!"ACTIVE".equals(destination.get("status")))
            throw new IllegalArgumentException("Both warehouses must be active");

        String transferPublicId=UUID.randomUUID().toString();
        try{
            jdbc.update("""
                    INSERT INTO stock_transfers(public_id,tenant_id,source_warehouse_id,destination_warehouse_id,
                      document_number,external_reference,transfer_type,status,requested_at,scheduled_at,
                      created_by_membership_id,notes)
                    VALUES(?,?,?,?,?,?,?,'DRAFT',UTC_TIMESTAMP(6),?,?,?)
                    """,transferPublicId,a.tenantId(),number(source,"id"),number(destination,"id"),document,
                    optional(command.externalReference(),100),type,command.scheduledAt(),a.membershipId(),
                    optional(command.notes(),1000));
        }catch(DuplicateKeyException duplicate){throw new IllegalStateException("Transfer document number already exists",duplicate);}
        long transferId=requiredLong("SELECT id FROM stock_transfers WHERE tenant_id=? AND public_id=?",a.tenantId(),transferPublicId,"Transfer was not created");
        recordCommand(a,transferId,command.requestId(),"CREATE");
        int lineNo=0;
        for(LineCommand line:command.lines()){
            lineNo++;
            createLine(a,transferId,number(source,"id"),number(destination,"id"),lineNo,line);
        }
        audit(a,transferPublicId,Map.of("status","NONE"),Map.of("status","DRAFT","lineCount",lineNo),command.requestId());
        return view(a.tenantId(),transferPublicId);
    }

    private void createLine(Access a,long transferId,long sourceWarehouse,long destinationWarehouse,int lineNo,LineCommand command){
        Objects.requireNonNull(command,"Transfer line is required");
        Map<String,Object> item=requiredRow("""
                SELECT id,base_uom_id,tracking_method,is_stock_item,status
                FROM items WHERE tenant_id=? AND public_id=?
                """,a.tenantId(),required(command.itemPublicId(),"item public ID"),"Item not found");
        if(!"ACTIVE".equals(item.get("status"))||!Boolean.TRUE.equals(bool(item.get("is_stock_item"))))
            throw new IllegalArgumentException("Transfer line must reference an active stock item");
        BigDecimal requested=quantity(command.requestedQty(),true);
        if(command.allocations()==null||command.allocations().isEmpty()||command.allocations().size()>200)
            throw new IllegalArgumentException("Each transfer line requires at least one allocation");
        long lineId;
        String linePublicId=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO stock_transfer_lines(public_id,tenant_id,stock_transfer_id,source_warehouse_id,
                  destination_warehouse_id,line_number,item_id,uom_id,requested_qty,status,notes)
                VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?)
                """,linePublicId,a.tenantId(),transferId,sourceWarehouse,destinationWarehouse,lineNo,
                number(item,"id"),number(item,"base_uom_id"),requested,optional(command.notes(),1000));
        lineId=requiredLong("SELECT id FROM stock_transfer_lines WHERE tenant_id=? AND public_id=?",a.tenantId(),linePublicId,"Transfer line was not created");
        BigDecimal allocationTotal=BigDecimal.ZERO.setScale(4);
        for(AllocationCommand allocation:command.allocations()){
            allocationTotal=allocationTotal.add(createAllocation(a,lineId,number(item,"id"),sourceWarehouse,destinationWarehouse,
                    Objects.toString(item.get("tracking_method")),allocation));
        }
        if(allocationTotal.compareTo(requested)!=0)
            throw new IllegalArgumentException("Allocation quantities must exactly equal requested line quantity");
    }

    private BigDecimal createAllocation(Access a,long lineId,long itemId,long sourceWarehouse,long destinationWarehouse,
                                        String tracking,AllocationCommand command){
        Objects.requireNonNull(command,"Transfer allocation is required");
        BigDecimal planned=quantity(command.plannedQty(),true);
        Map<String,Object> source=requiredRow("""
                SELECT id,status FROM warehouse_locations
                WHERE tenant_id=? AND warehouse_id=? AND public_id=?
                """,a.tenantId(),sourceWarehouse,required(command.sourceLocationPublicId(),"source location public ID"),"Source location not found");
        Map<String,Object> destination=requiredRow("""
                SELECT id,status FROM warehouse_locations
                WHERE tenant_id=? AND warehouse_id=? AND public_id=?
                """,a.tenantId(),destinationWarehouse,required(command.destinationLocationPublicId(),"destination location public ID"),"Destination location not found");
        if(!"ACTIVE".equals(source.get("status"))||!"ACTIVE".equals(destination.get("status")))
            throw new IllegalArgumentException("Transfer locations must be active");
        Long lotId=null;
        if(command.lotPublicId()!=null&&!command.lotPublicId().isBlank()){
            lotId=requiredLong("SELECT id FROM inventory_lots WHERE tenant_id=? AND item_id=? AND public_id=? AND status='ACTIVE'",
                    a.tenantId(),itemId,command.lotPublicId().trim(),"Active item lot not found");
        }
        boolean lotTracked=Set.of("LOT","LOT_AND_SERIAL").contains(tracking);
        boolean serialTracked=Set.of("SERIAL","LOT_AND_SERIAL").contains(tracking);
        if(lotTracked!=(lotId!=null))throw new IllegalArgumentException("Lot allocation does not match item tracking method");
        List<String> serials=command.serialPublicIds()==null?List.of():command.serialPublicIds();
        if(new HashSet<>(serials).size()!=serials.size())throw new IllegalArgumentException("Duplicate serial in allocation");
        if(serialTracked){
            if(planned.stripTrailingZeros().scale()>0||planned.intValueExact()!=serials.size())
                throw new IllegalArgumentException("Serial allocation quantity must equal the number of serial identities");
        }else if(!serials.isEmpty())throw new IllegalArgumentException("Non-serial item cannot include serial identities");
        String allocationPublicId=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO stock_transfer_allocations(public_id,tenant_id,stock_transfer_line_id,item_id,
                  source_warehouse_id,source_location_id,destination_warehouse_id,destination_location_id,
                  lot_id,planned_qty,status)
                VALUES(?,?,?,?,?,?,?,?,?,?,'PLANNED')
                """,allocationPublicId,a.tenantId(),lineId,itemId,sourceWarehouse,number(source,"id"),
                destinationWarehouse,number(destination,"id"),lotId,planned);
        long allocationId=requiredLong("SELECT id FROM stock_transfer_allocations WHERE tenant_id=? AND public_id=?",
                a.tenantId(),allocationPublicId,"Transfer allocation was not created");
        for(String serialPublicId:serials){
            Map<String,Object> serial=requiredRow("""
                    SELECT id FROM inventory_serials
                    WHERE tenant_id=? AND item_id=? AND public_id=? AND status='AVAILABLE'
                      AND current_warehouse_id=? AND current_location_id=? AND lot_id <=> ?
                    """,a.tenantId(),itemId,required(serialPublicId,"serial public ID"),sourceWarehouse,number(source,"id"),lotId,
                    "Serial is not available at the selected source allocation");
            long activeAssignments=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM stock_transfer_line_serials ts
                    JOIN stock_transfer_allocations olda ON olda.id=ts.stock_transfer_allocation_id AND olda.tenant_id=ts.tenant_id
                    JOIN stock_transfer_lines oldl ON oldl.id=olda.stock_transfer_line_id AND oldl.tenant_id=olda.tenant_id
                    JOIN stock_transfers oldt ON oldt.id=oldl.stock_transfer_id AND oldt.tenant_id=oldl.tenant_id
                    WHERE ts.tenant_id=? AND ts.serial_id=? AND ts.status IN ('PLANNED','DISPATCHED','IN_TRANSIT')
                      AND oldt.status NOT IN ('RECEIVED','CANCELLED')
                    """,Long.class,a.tenantId(),number(serial,"id"));
            if(activeAssignments>0)throw new IllegalStateException("Serial is already assigned to another active transfer");
            jdbc.update("""
                    INSERT INTO stock_transfer_line_serials(tenant_id,stock_transfer_allocation_id,item_id,serial_id,status)
                    VALUES(?,?,?,?,'PLANNED')
                    """,a.tenantId(),allocationId,itemId,number(serial,"id"));
        }
        return planned;
    }

    @Transactional
    public Map<String,Object> release(WmsAuthenticatedPrincipal principal,String transferPublicId,UUID requestId){
        Objects.requireNonNull(requestId,"Request ID is required");
        lockTenant(principal);
        Access a=access(principal,"WMS_TRANSFER_RELEASE");
        Map<String,Object> h=header(a.tenantId(),transferPublicId,true);
        if(!"DRAFT".equals(h.get("status")))throw new IllegalStateException("Only a draft transfer can be released");
        List<Map<String,Object>> lines=jdbc.queryForList("""
                SELECT l.id,l.item_id,l.requested_qty,l.status
                FROM stock_transfer_lines l WHERE l.tenant_id=? AND l.stock_transfer_id=? ORDER BY l.id
                """,a.tenantId(),number(h,"id"));
        if(lines.isEmpty())throw new IllegalStateException("Transfer has no lines");
        Map<String,BigDecimal> demand=new HashMap<>();
        Map<String,Map<String,Object>> buckets=new HashMap<>();
        for(Map<String,Object> line:lines){
            List<Map<String,Object>> allocations=jdbc.queryForList("""
                    SELECT a.id,a.item_id,a.source_warehouse_id,a.source_location_id,a.lot_id,a.planned_qty,
                           i.tracking_method
                    FROM stock_transfer_allocations a JOIN items i ON i.id=a.item_id AND i.tenant_id=a.tenant_id
                    WHERE a.tenant_id=? AND a.stock_transfer_line_id=? ORDER BY a.id
                    """,a.tenantId(),number(line,"id"));
            BigDecimal sum=BigDecimal.ZERO.setScale(4);
            for(Map<String,Object> allocation:allocations){
                BigDecimal planned=decimal(allocation.get("planned_qty"));sum=sum.add(planned);
                String key=number(allocation,"source_location_id")+":"+number(allocation,"item_id")+":"+Objects.toString(allocation.get("lot_id"),"0");
                demand.merge(key,planned,BigDecimal::add);buckets.put(key,allocation);
                if(Set.of("SERIAL","LOT_AND_SERIAL").contains(Objects.toString(allocation.get("tracking_method")))){
                    long count=jdbc.queryForObject("SELECT COUNT(*) FROM stock_transfer_line_serials WHERE tenant_id=? AND stock_transfer_allocation_id=? AND status='PLANNED'",
                            Long.class,a.tenantId(),number(allocation,"id"));
                    if(new BigDecimal(count).compareTo(planned)!=0)throw new IllegalStateException("Serial allocation count does not match planned quantity");
                }
            }
            if(sum.compareTo(decimal(line.get("requested_qty")))!=0)
                throw new IllegalStateException("Every transfer line must be fully allocated before release");
        }
        for(var entry:demand.entrySet()){
            Map<String,Object> b=buckets.get(entry.getKey());
            BigDecimal available=jdbc.queryForObject("""
                    SELECT COALESCE(SUM(available_qty),0) FROM inventory_balances
                    WHERE tenant_id=? AND warehouse_id=? AND location_id=? AND item_id=?
                      AND lot_id <=> ? AND stock_status='AVAILABLE'
                    """,BigDecimal.class,a.tenantId(),number(b,"source_warehouse_id"),number(b,"source_location_id"),
                    number(b,"item_id"),b.get("lot_id"));
            if(available.compareTo(entry.getValue())<0)throw new IllegalStateException("Insufficient available stock to release transfer");
        }
        recordCommand(a,number(h,"id"),requestId,"RELEASE");
        jdbc.update("UPDATE stock_transfers SET status='RELEASED',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND tenant_id=? AND status='DRAFT'",
                number(h,"id"),a.tenantId());
        jdbc.update("UPDATE stock_transfer_lines SET status='READY',updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND stock_transfer_id=?",
                a.tenantId(),number(h,"id"));
        audit(a,transferPublicId,Map.of("status","DRAFT"),Map.of("status","RELEASED"),requestId);
        return get(principal,transferPublicId);
    }

    @Transactional
    public Map<String,Object> dispatch(WmsAuthenticatedPrincipal principal,String transferPublicId,DispatchCommand command){
        Objects.requireNonNull(command,"Dispatch request is required");
        Objects.requireNonNull(command.requestId(),"Request ID is required");
        if(command.allocations()==null||command.allocations().isEmpty())throw new IllegalArgumentException("At least one allocation must be dispatched");
        lockTenant(principal);
        Access a=access(principal,"WMS_TRANSFER_DISPATCH");
        Map<String,Object> h=header(a.tenantId(),transferPublicId,true);
        if(!Set.of("RELEASED","IN_TRANSIT","PARTIALLY_RECEIVED").contains(h.get("status")))
            throw new IllegalStateException("Transfer is not in a dispatchable state");
        recordCommand(a,number(h,"id"),command.requestId(),"DISPATCH");
        List<WmsInventoryPostingService.PostingLeg> legs=new ArrayList<>();
        List<DispatchUpdate> updates=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(DispatchAllocation item:command.allocations()){
            if(!seen.add(item.allocationPublicId()))throw new IllegalArgumentException("Duplicate allocation in dispatch");
            Map<String,Object> al=allocation(a.tenantId(),number(h,"id"),item.allocationPublicId(),true);
            BigDecimal qty=quantity(item.quantity(),true);
            BigDecimal remaining=decimal(al.get("planned_qty")).subtract(decimal(al.get("dispatched_qty")));
            if(qty.compareTo(remaining)>0)throw new IllegalArgumentException("Dispatch exceeds remaining planned quantity");
            String tracking=Objects.toString(al.get("tracking_method"));
            List<String> serialIds=item.serialPublicIds()==null?List.of():item.serialPublicIds();
            if(Set.of("SERIAL","LOT_AND_SERIAL").contains(tracking)){
                if(qty.stripTrailingZeros().scale()>0||qty.intValueExact()!=serialIds.size())
                    throw new IllegalArgumentException("Dispatch serial count must equal dispatched quantity");
                if(new HashSet<>(serialIds).size()!=serialIds.size())throw new IllegalArgumentException("Duplicate serial in dispatch");
                for(String serialId:serialIds){
                    linkedSerial(a.tenantId(),number(al,"id"),serialId,"PLANNED");
                    legs.add(postingLeg(al,serialId,"AVAILABLE",BigDecimal.ONE,false));
                    updates.add(new DispatchUpdate(number(al,"id"),serialId));
                }
            }else{
                if(!serialIds.isEmpty())throw new IllegalArgumentException("Non-serial allocation cannot dispatch serial identities");
                legs.add(postingLeg(al,null,"AVAILABLE",qty,false));
                updates.add(new DispatchUpdate(number(al,"id"),null,qty));
            }
        }
        postingService.postTransferDispatch(principal,new WmsInventoryPostingService.PostingCommand(
                command.requestId(),"TRANSFER_DISPATCH",legs,optional(command.notes(),1000)));
        for(DispatchUpdate u:updates){
            if(u.serialPublicId()!=null){
                jdbc.update("""
                        UPDATE stock_transfer_line_serials ts
                        JOIN inventory_serials s ON s.id=ts.serial_id AND s.tenant_id=ts.tenant_id
                        SET ts.status='IN_TRANSIT',ts.dispatched_at=UTC_TIMESTAMP(6)
                        WHERE ts.tenant_id=? AND ts.stock_transfer_allocation_id=? AND s.public_id=? AND ts.status='PLANNED'
                        """,a.tenantId(),u.allocationId(),u.serialPublicId());
            }else if(u.quantity()!=null){
                jdbc.update("""
                        UPDATE stock_transfer_allocations SET dispatched_qty=dispatched_qty+?,
                          status='IN_TRANSIT',updated_at=UTC_TIMESTAMP(6)
                        WHERE id=? AND tenant_id=?
                        """,u.quantity(),u.allocationId(),a.tenantId());
                jdbc.update("""
                        UPDATE stock_transfer_lines l
                        JOIN stock_transfer_allocations a ON a.stock_transfer_line_id=l.id AND a.tenant_id=l.tenant_id
                        SET l.dispatched_qty=l.dispatched_qty+?,l.status=CASE WHEN l.received_qty+l.damaged_qty+l.short_qty>0 THEN 'PARTIALLY_RECEIVED' ELSE 'DISPATCHED' END,l.updated_at=UTC_TIMESTAMP(6)
                        WHERE a.id=? AND l.tenant_id=?
                        """,u.quantity(),u.allocationId(),a.tenantId());
            }
        }
        // Serial allocations increment their quantity counters by the number of newly dispatched serials.
        for(DispatchUpdate u:updates)if(u.serialPublicId()!=null){
            jdbc.update("UPDATE stock_transfer_allocations SET dispatched_qty=dispatched_qty+1,status='IN_TRANSIT',updated_at=UTC_TIMESTAMP(6) WHERE id=? AND tenant_id=?",
                    u.allocationId(),a.tenantId());
            jdbc.update("""
                    UPDATE stock_transfer_lines l JOIN stock_transfer_allocations a
                      ON a.stock_transfer_line_id=l.id AND a.tenant_id=l.tenant_id
                    SET l.dispatched_qty=l.dispatched_qty+1,l.status=CASE WHEN l.received_qty+l.damaged_qty+l.short_qty>0 THEN 'PARTIALLY_RECEIVED' ELSE 'DISPATCHED' END,l.updated_at=UTC_TIMESTAMP(6)
                    WHERE a.id=? AND l.tenant_id=?
                    """,u.allocationId(),a.tenantId());
        }
        jdbc.update("""
                UPDATE stock_transfers SET status='IN_TRANSIT',
                  dispatched_at=COALESCE(dispatched_at,UTC_TIMESTAMP(6)),dispatched_by_membership_id=?,
                  version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=?
                """,a.membershipId(),number(h,"id"),a.tenantId());
        audit(a,transferPublicId,Map.of("status",h.get("status")),
                Map.of("status","IN_TRANSIT","requestId",command.requestId().toString()),command.requestId());
        return get(principal,transferPublicId);
    }

    @Transactional
    public Map<String,Object> receive(WmsAuthenticatedPrincipal principal,String transferPublicId,ReceiveCommand command){
        Objects.requireNonNull(command,"Receipt request is required");
        Objects.requireNonNull(command.requestId(),"Request ID is required");
        if(command.allocations()==null||command.allocations().isEmpty())throw new IllegalArgumentException("At least one allocation must be received");
        lockTenant(principal);
        Access a=access(principal,"WMS_TRANSFER_RECEIVE");
        Map<String,Object> h=header(a.tenantId(),transferPublicId,true);
        if(!Set.of("IN_TRANSIT","PARTIALLY_RECEIVED").contains(h.get("status")))
            throw new IllegalStateException("Transfer has no in-transit stock to receive");
        recordCommand(a,number(h,"id"),command.requestId(),"RECEIVE");
        List<WmsInventoryPostingService.PostingLeg> postingLegs=new ArrayList<>();
        List<ReceiptUpdate> updates=new ArrayList<>();
        List<String> lostSerials=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(ReceiveAllocation item:command.allocations()){
            if(!seen.add(item.allocationPublicId()))throw new IllegalArgumentException("Duplicate allocation in receipt");
            Map<String,Object> al=allocation(a.tenantId(),number(h,"id"),item.allocationPublicId(),true);
            BigDecimal good=quantity(item.receivedQty()==null?BigDecimal.ZERO:item.receivedQty(),false);
            BigDecimal damaged=quantity(item.damagedQty()==null?BigDecimal.ZERO:item.damagedQty(),false);
            BigDecimal shortage=quantity(item.shortQty()==null?BigDecimal.ZERO:item.shortQty(),false);
            BigDecimal total=good.add(damaged).add(shortage);
            BigDecimal outstanding=decimal(al.get("dispatched_qty")).subtract(decimal(al.get("received_qty")))
                    .subtract(decimal(al.get("damaged_qty"))).subtract(decimal(al.get("short_qty")));
            if(total.signum()<=0||total.compareTo(outstanding)>0)
                throw new IllegalArgumentException("Receipt reconciliation exceeds outstanding dispatched quantity");
            String tracking=Objects.toString(al.get("tracking_method"));
            List<String> goodSerials=item.receivedSerialPublicIds()==null?List.of():item.receivedSerialPublicIds();
            List<String> damagedSerials=item.damagedSerialPublicIds()==null?List.of():item.damagedSerialPublicIds();
            List<String> shortSerials=item.shortSerialPublicIds()==null?List.of():item.shortSerialPublicIds();
            if(Set.of("SERIAL","LOT_AND_SERIAL").contains(tracking)){
                if(good.stripTrailingZeros().scale()>0||damaged.stripTrailingZeros().scale()>0||shortage.stripTrailingZeros().scale()>0
                        ||good.intValueExact()!=goodSerials.size()||damaged.intValueExact()!=damagedSerials.size()
                        ||shortage.intValueExact()!=shortSerials.size())
                    throw new IllegalArgumentException("Receipt serial identities must exactly match good, damaged and short quantities");
                Set<String> all=new HashSet<>(goodSerials);
                for(String serial:damagedSerials)if(!all.add(serial))throw new IllegalArgumentException("Duplicate serial in receipt reconciliation");
                for(String serial:shortSerials)if(!all.add(serial))throw new IllegalArgumentException("Duplicate serial in receipt reconciliation");
                for(String serial:goodSerials){linkedSerial(a.tenantId(),number(al,"id"),serial,"IN_TRANSIT");postingLegs.add(postingLeg(al,serial,"AVAILABLE",BigDecimal.ONE,true));updates.add(new ReceiptUpdate(number(al,"id"),serial,"RECEIVED"));}
                for(String serial:damagedSerials){linkedSerial(a.tenantId(),number(al,"id"),serial,"IN_TRANSIT");postingLegs.add(postingLeg(al,serial,"DAMAGED",BigDecimal.ONE,true));updates.add(new ReceiptUpdate(number(al,"id"),serial,"DAMAGED"));}
                for(String serial:shortSerials){linkedSerial(a.tenantId(),number(al,"id"),serial,"IN_TRANSIT");lostSerials.add(serial);updates.add(new ReceiptUpdate(number(al,"id"),serial,"LOST"));}
            }else{
                if(!goodSerials.isEmpty()||!damagedSerials.isEmpty()||!shortSerials.isEmpty())
                    throw new IllegalArgumentException("Non-serial allocation cannot receive serial identities");
                if(good.signum()>0)postingLegs.add(postingLeg(al,null,"AVAILABLE",good,true));
                if(damaged.signum()>0)postingLegs.add(postingLeg(al,null,"DAMAGED",damaged,true));
            }
            updates.add(new ReceiptUpdate(number(al,"id"),null,"COUNTERS",good,damaged,shortage));
        }
        if(!postingLegs.isEmpty())postingService.postTransferReceipt(principal,new WmsInventoryPostingService.PostingCommand(
                command.requestId(),"TRANSFER_RECEIPT",postingLegs,optional(command.notes(),1000)));
        for(String serial:lostSerials)postingService.markTransferSerialLost(principal,serial);
        for(ReceiptUpdate u:updates){
            if(u.serialPublicId()!=null){
                jdbc.update("""
                        UPDATE stock_transfer_line_serials ts
                        JOIN inventory_serials s ON s.id=ts.serial_id AND s.tenant_id=ts.tenant_id
                        SET ts.status=?,ts.received_at=UTC_TIMESTAMP(6)
                        WHERE ts.tenant_id=? AND ts.stock_transfer_allocation_id=? AND s.public_id=? AND ts.status='IN_TRANSIT'
                        """,u.status(),a.tenantId(),u.allocationId(),u.serialPublicId());
            }else if("COUNTERS".equals(u.status())){
                jdbc.update("""
                        UPDATE stock_transfer_allocations SET
                          status=CASE
                            WHEN dispatched_qty=planned_qty AND dispatched_qty=received_qty+?+damaged_qty+?+short_qty+? THEN 'RECEIVED'
                            WHEN received_qty+?+damaged_qty+?+short_qty+?>0 THEN 'PARTIALLY_RECEIVED'
                            ELSE 'IN_TRANSIT' END,
                          received_qty=received_qty+?,damaged_qty=damaged_qty+?,short_qty=short_qty+?,
                          updated_at=UTC_TIMESTAMP(6)
                        WHERE id=? AND tenant_id=?
                        """,u.good(),u.damaged(),u.shortage(),u.good(),u.damaged(),u.shortage(),u.good(),u.damaged(),u.shortage(),u.allocationId(),a.tenantId());
                jdbc.update("""
                        UPDATE stock_transfer_lines l
                        JOIN stock_transfer_allocations a ON a.stock_transfer_line_id=l.id AND a.tenant_id=l.tenant_id
                        SET l.received_qty=l.received_qty+?,l.damaged_qty=l.damaged_qty+?,l.short_qty=l.short_qty+?,
                            l.status='PARTIALLY_RECEIVED',l.updated_at=UTC_TIMESTAMP(6)
                        WHERE a.id=? AND l.tenant_id=?
                        """,u.good(),u.damaged(),u.shortage(),u.allocationId(),a.tenantId());
            }
        }
        // Recalculate header state from all allocation quantities; undispatched planned stock keeps it IN_TRANSIT.
        Map<String,Object> totals=requiredRow("""
                SELECT COALESCE(SUM(planned_qty-dispatched_qty),0) undispatched,
                       COALESCE(SUM(dispatched_qty-received_qty-damaged_qty-short_qty),0) outstanding,
                       COALESCE(SUM(received_qty+damaged_qty+short_qty),0) reconciled
                FROM stock_transfer_allocations WHERE tenant_id=? AND stock_transfer_line_id IN
                  (SELECT id FROM stock_transfer_lines WHERE tenant_id=? AND stock_transfer_id=?)
                """,a.tenantId(),a.tenantId(),number(h,"id"),"Transfer allocations not found");
        String next=decimal(totals.get("undispatched")).signum()>0?"IN_TRANSIT":
                decimal(totals.get("outstanding")).signum()>0?"PARTIALLY_RECEIVED":"RECEIVED";
        jdbc.update("""
                UPDATE stock_transfers SET status=?,received_at=CASE WHEN ?='RECEIVED' THEN UTC_TIMESTAMP(6) ELSE received_at END,
                  received_by_membership_id=?,version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=?
                """,next,next,a.membershipId(),number(h,"id"),a.tenantId());
        jdbc.update("""
                UPDATE stock_transfer_lines l
                SET l.status=CASE
                  WHEN (SELECT COALESCE(SUM(a.dispatched_qty),0) FROM stock_transfer_allocations a WHERE a.tenant_id=l.tenant_id AND a.stock_transfer_line_id=l.id)=l.requested_qty
                   AND (SELECT COALESCE(SUM(a.received_qty+a.damaged_qty+a.short_qty),0) FROM stock_transfer_allocations a WHERE a.tenant_id=l.tenant_id AND a.stock_transfer_line_id=l.id)=l.requested_qty THEN 'RECEIVED'
                  WHEN (SELECT COALESCE(SUM(a.received_qty+a.damaged_qty+a.short_qty),0) FROM stock_transfer_allocations a WHERE a.tenant_id=l.tenant_id AND a.stock_transfer_line_id=l.id)>0 THEN 'PARTIALLY_RECEIVED'
                  ELSE l.status END, l.updated_at=UTC_TIMESTAMP(6)
                WHERE l.tenant_id=? AND l.stock_transfer_id=?
                """,a.tenantId(),number(h,"id"));
        audit(a,transferPublicId,Map.of("status",h.get("status")),
                Map.of("status",next,"requestId",command.requestId().toString()),command.requestId());
        return get(principal,transferPublicId);
    }

    private WmsInventoryPostingService.PostingLeg postingLeg(Map<String,Object> a,String serialPublicId,String stockStatus,BigDecimal qty,boolean destination){
        return new WmsInventoryPostingService.PostingLeg(
                Objects.toString(a.get(destination?"destination_warehouse_public_id":"source_warehouse_public_id")),
                Objects.toString(a.get(destination?"destination_location_public_id":"source_location_public_id")),
                Objects.toString(a.get("item_public_id")), (String)a.get("lot_public_id"), serialPublicId,null,stockStatus,qty);
    }

    private Map<String,Object> header(long tenantId,String publicId,boolean lock){
        String sql="""
                SELECT t.*,sw.public_id source_warehouse_public_id,dw.public_id destination_warehouse_public_id
                FROM stock_transfers t
                JOIN warehouses sw ON sw.id=t.source_warehouse_id AND sw.tenant_id=t.tenant_id
                JOIN warehouses dw ON dw.id=t.destination_warehouse_id AND dw.tenant_id=t.tenant_id
                WHERE t.tenant_id=? AND t.public_id=?
                """+(lock?" FOR UPDATE":"");
        List<Map<String,Object>> rows=jdbc.queryForList(sql,tenantId,required(publicId,"transfer public ID"));
        if(rows.isEmpty())throw new IllegalArgumentException("Transfer not found");
        return rows.getFirst();
    }

    private Map<String,Object> allocation(long tenantId,long transferId,String publicId,boolean lock){
        String sql="""
                SELECT a.*,l.stock_transfer_id,i.public_id item_public_id,i.tracking_method,
                       sw.public_id source_warehouse_public_id,dw.public_id destination_warehouse_public_id,
                       sl.public_id source_location_public_id,dl.public_id destination_location_public_id,
                       lot.public_id lot_public_id
                FROM stock_transfer_allocations a
                JOIN stock_transfer_lines l ON l.id=a.stock_transfer_line_id AND l.tenant_id=a.tenant_id
                JOIN items i ON i.id=a.item_id AND i.tenant_id=a.tenant_id
                JOIN warehouses sw ON sw.id=a.source_warehouse_id AND sw.tenant_id=a.tenant_id
                JOIN warehouses dw ON dw.id=a.destination_warehouse_id AND dw.tenant_id=a.tenant_id
                JOIN warehouse_locations sl ON sl.id=a.source_location_id AND sl.warehouse_id=a.source_warehouse_id AND sl.tenant_id=a.tenant_id
                JOIN warehouse_locations dl ON dl.id=a.destination_location_id AND dl.warehouse_id=a.destination_warehouse_id AND dl.tenant_id=a.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id=a.lot_id AND lot.tenant_id=a.tenant_id
                WHERE a.tenant_id=? AND l.stock_transfer_id=? AND a.public_id=?
                """+(lock?" FOR UPDATE":"");
        List<Map<String,Object>> rows=jdbc.queryForList(sql,tenantId,transferId,required(publicId,"allocation public ID"));
        if(rows.isEmpty())throw new IllegalArgumentException("Transfer allocation not found");
        return rows.getFirst();
    }

    private Map<String,Object> linkedSerial(long tenantId,long allocationId,String serialPublicId,String status){
        List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT s.id FROM stock_transfer_line_serials ts
                JOIN inventory_serials s ON s.id=ts.serial_id AND s.tenant_id=ts.tenant_id
                WHERE ts.tenant_id=? AND ts.stock_transfer_allocation_id=? AND s.public_id=? AND ts.status=?
                """,tenantId,allocationId,required(serialPublicId,"serial public ID"),status);
        if(rows.isEmpty())throw new IllegalArgumentException("Serial is not assigned to this allocation in the required state");
        return rows.getFirst();
    }

    private void recordCommand(Access a,long transferId,UUID requestId,String type){
        try{
            jdbc.update("INSERT INTO stock_transfer_commands(tenant_id,stock_transfer_id,request_id,command_type) VALUES(?,?,?,?)",
                    a.tenantId(),transferId,requestId.toString(),type);
        }catch(DuplicateKeyException duplicate){throw new IllegalStateException("Transfer request ID has already been used",duplicate);}
    }

    private void audit(Access a,String subject,Map<String,Object> before,Map<String,Object> after,UUID requestId){
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("before",before);payload.put("after",after);payload.put("requestId",requestId.toString());
        final String json;
        try{json=mapper.writeValueAsString(payload);}catch(JsonProcessingException e){throw new IllegalStateException("Transfer audit serialization failed",e);}
        int count=jdbc.update("""
                INSERT INTO tenant_audit_events(public_id,tenant_id,actor_membership_id,actor_type,operation_ref,
                  event_type,subject_public_id,change_payload)
                SELECT ?,t.id,m.id,'TENANT_MEMBER',?,'WMS_STOCK_TRANSFER_UPDATED',?,?
                FROM tenants t JOIN tenant_memberships m ON m.id=? AND m.tenant_id=t.id AND m.status='ACTIVE'
                JOIN users u ON u.id=m.user_id AND u.public_id=? AND u.status='ACTIVE'
                WHERE t.id=? AND t.status='ACTIVE'
                """,UUID.randomUUID().toString(),requestId.toString(),subject,json,a.membershipId(),a.userPublicId(),a.tenantId());
        if(count!=1)throw new AccessDeniedException("Audit actor is no longer active");
    }

    private Access access(WmsAuthenticatedPrincipal principal,String permission){
        if(principal==null)throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess resolved=accessService.resolve(principal.userPublicId(),principal.membershipPublicId());
        if(!Objects.equals(resolved.userPublicId(),principal.userPublicId())
                ||!Objects.equals(resolved.tenantId(),principal.tenantId())
                ||!Objects.equals(resolved.tenantPublicId(),principal.tenantPublicId())
                ||!Objects.equals(resolved.membershipId(),principal.membershipId())
                ||!Objects.equals(resolved.membershipPublicId(),principal.membershipPublicId()))
            throw new AccessDeniedException("Tenant context is unavailable");
        if(!resolved.hasPermission(permission))throw new AccessDeniedException("Required transfer permission is missing");
        return new Access(resolved.tenantId(),resolved.membershipId(),resolved.userPublicId());
    }

    private void lockTenant(WmsAuthenticatedPrincipal p){
        if(p==null)throw new AccessDeniedException("Tenant context is required");
        List<Long> ids=jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE",Long.class,p.tenantId());
        if(ids.isEmpty())throw new AccessDeniedException("Active tenant is unavailable");
    }

    private Map<String,Object> requiredRow(String sql,Object a,Object b,String message){
        List<Map<String,Object>> rows=jdbc.queryForList(sql,a,b);if(rows.isEmpty())throw new IllegalArgumentException(message);return rows.getFirst();
    }
    private Map<String,Object> requiredRow(String sql,Object a,Object b,Object c,String message){
        List<Map<String,Object>> rows=jdbc.queryForList(sql,a,b,c);if(rows.isEmpty())throw new IllegalArgumentException(message);return rows.getFirst();
    }
    private Map<String,Object> requiredRow(String sql,Object a,Object b,Object c,Object d,Object e,Object f,String message){
        List<Map<String,Object>> rows=jdbc.queryForList(sql,a,b,c,d,e,f);if(rows.isEmpty())throw new IllegalArgumentException(message);return rows.getFirst();
    }
    private long requiredLong(String sql,Object a,Object b,String message){
        List<Long> rows=jdbc.queryForList(sql,Long.class,a,b);if(rows.isEmpty())throw new IllegalArgumentException(message);return rows.getFirst();
    }
    private long requiredLong(String sql,Object a,Object b,Object c,String message){
        List<Long> rows=jdbc.queryForList(sql,Long.class,a,b,c);if(rows.isEmpty())throw new IllegalArgumentException(message);return rows.getFirst();
    }
    private long requiredLong(String sql,Object a,Object b,Object c,Object d,Object e,Object f,String message){
        List<Long> rows=jdbc.queryForList(sql,Long.class,a,b,c,d,e,f);if(rows.isEmpty())throw new IllegalArgumentException(message);return rows.getFirst();
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static boolean bool(Object v){return v instanceof Boolean b?b: v instanceof Number n&&n.intValue()!=0;}
    private static BigDecimal decimal(Object v){return v instanceof BigDecimal b?b:new BigDecimal(v.toString());}
    private static String required(String v,String label){if(v==null||v.isBlank())throw new IllegalArgumentException(label+" is required");return v.trim();}
    private static String required(String v,String label,int max){String s=required(v,label);if(s.length()>max)throw new IllegalArgumentException(label+" is too long");return s;}
    private static String optional(String v,int max){if(v==null||v.isBlank())return null;String s=v.trim();if(s.length()>max)throw new IllegalArgumentException("Optional text is too long");return s;}
    private static String enumValue(String v,Set<String> allowed,String label){String s=required(v,label).toUpperCase(Locale.ROOT);if(!allowed.contains(s))throw new IllegalArgumentException(label+" is invalid");return s;}
    private static BigDecimal quantity(BigDecimal v,boolean positive){
        if(v==null||(positive?v.signum()<=0:v.signum()<0))throw new IllegalArgumentException(positive?"Quantity must be positive":"Quantity cannot be negative");
        try{return v.setScale(4,RoundingMode.UNNECESSARY);}catch(ArithmeticException e){throw new IllegalArgumentException("Quantity supports at most four decimal places",e);}
    }

    public record TransferCommand(UUID requestId,String sourceWarehousePublicId,String destinationWarehousePublicId,String documentNumber,
                                 String externalReference,String transferType,java.time.LocalDateTime scheduledAt,String notes,
                                 List<LineCommand> lines){}
    public record LineCommand(String itemPublicId,BigDecimal requestedQty,String notes,List<AllocationCommand> allocations){}
    public record AllocationCommand(String sourceLocationPublicId,String destinationLocationPublicId,String lotPublicId,
                                    BigDecimal plannedQty,List<String> serialPublicIds){}
    public record DispatchCommand(UUID requestId,List<DispatchAllocation> allocations,String notes){}
    public record DispatchAllocation(String allocationPublicId,BigDecimal quantity,List<String> serialPublicIds){}
    public record ReceiveCommand(UUID requestId,List<ReceiveAllocation> allocations,String notes){}
    public record ReceiveAllocation(String allocationPublicId,BigDecimal receivedQty,BigDecimal damagedQty,BigDecimal shortQty,
                                    List<String> receivedSerialPublicIds,List<String> damagedSerialPublicIds,List<String> shortSerialPublicIds){}
    private record Access(long tenantId,long membershipId,String userPublicId){}
    private record DispatchUpdate(long allocationId,String serialPublicId,BigDecimal quantity){
        DispatchUpdate(long allocationId,String serialPublicId){this(allocationId,serialPublicId,null);}
    }
    private record ReceiptUpdate(long allocationId,String serialPublicId,String status,BigDecimal good,BigDecimal damaged,BigDecimal shortage){
        ReceiptUpdate(long allocationId,String serialPublicId,String status){this(allocationId,serialPublicId,status,null,null,null);}
    }
}
