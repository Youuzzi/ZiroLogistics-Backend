package com.zirocraft.zirologistics.wms.picking.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryReservationService;
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

/** Tenant-scoped picking execution. Stock writes remain inside inventory application boundaries. */
@Service
@Profile("wms")
public class WmsPickingManagementService {
    private final WmsMembershipAccessService accessService;
    private final WmsInventoryReservationService reservationService;
    private final WmsInventoryPostingService postingService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsPickingManagementService(WmsMembershipAccessService accessService,
                                       WmsInventoryReservationService reservationService,
                                       WmsInventoryPostingService postingService,
                                       JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.reservationService = Objects.requireNonNull(reservationService);
        this.postingService = Objects.requireNonNull(postingService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(WmsAuthenticatedPrincipal principal) {
        Access a = access(principal, "WMS_PICKING_READ");
        return jdbc.queryForList("""
                SELECT t.public_id, t.task_number, t.status, t.priority, t.created_at,
                       t.started_at, t.completed_at, s.public_id AS shipment_public_id,
                       s.document_number, w.public_id AS warehouse_public_id,
                       m.public_id AS assigned_to_membership_public_id
                FROM picking_tasks t
                JOIN outbound_shipments s ON s.id=t.outbound_shipment_id AND s.tenant_id=t.tenant_id
                JOIN warehouses w ON w.id=t.warehouse_id AND w.tenant_id=t.tenant_id
                LEFT JOIN tenant_memberships m ON m.id=t.assigned_to_membership_id AND m.tenant_id=t.tenant_id
                WHERE t.tenant_id=? ORDER BY t.created_at DESC,t.id DESC LIMIT 200
                """, a.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String,Object> get(WmsAuthenticatedPrincipal principal, String taskPublicId) {
        Access a = access(principal, "WMS_PICKING_READ");
        Map<String,Object> task = task(a.tenantId(), taskPublicId);
        Map<String,Object> result = new LinkedHashMap<>(task);
        result.remove("id"); result.remove("warehouse_id"); result.remove("outbound_shipment_id");
        result.put("lines", jdbc.queryForList("""
                SELECT l.line_number,l.planned_qty,l.picked_qty,l.status,l.notes,
                       i.public_id item_public_id,i.sku,i.item_name,
                       src.public_id source_location_public_id,src.location_code source_location_code,
                       a.public_id allocation_public_id,a.allocated_qty,
                       lot.public_id lot_public_id,lot.lot_number,
                       (SELECT COUNT(*) FROM picking_task_line_serials ps
                        WHERE ps.tenant_id=l.tenant_id AND ps.picking_task_line_id=l.id AND ps.status='ALLOCATED') serials_remaining
                FROM picking_task_lines l
                JOIN items i ON i.id=l.item_id AND i.tenant_id=l.tenant_id
                JOIN warehouse_locations src ON src.id=l.source_location_id AND src.tenant_id=l.tenant_id
                JOIN outbound_shipment_allocations a ON a.id=l.outbound_shipment_allocation_id AND a.tenant_id=l.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id=a.lot_id AND lot.tenant_id=a.tenant_id
                WHERE l.tenant_id=? AND l.picking_task_id=? ORDER BY l.line_number
                """, a.tenantId(), number(task,"id")));
        return result;
    }

    /** One task is generated from all released shipment allocations; V1 already contains its tables. */
    @Transactional
    public Map<String,Object> create(WmsAuthenticatedPrincipal principal, CreatePickingCommand command) {
        if(command==null) throw new IllegalArgumentException("Picking request is required");
        lockTenant(principal);
        Access a=access(principal,"WMS_PICKING_CREATE");
        String taskNo=required(command.taskNumber(),"task number",60).toUpperCase(Locale.ROOT);
        String priority=enumValue(command.priority()==null?"NORMAL":command.priority(),Set.of("LOW","NORMAL","HIGH","URGENT"));
        Map<String,Object> shipment=requiredRow("""
                SELECT id,warehouse_id,status,public_id,document_number
                FROM outbound_shipments WHERE tenant_id=? AND public_id=?
                FOR UPDATE
                """,a.tenantId(),required(command.shipmentPublicId(),"shipment public ID"));
        if(!"RELEASED".equals(shipment.get("status"))) throw new IllegalStateException("Picking tasks can only be created for released shipments");
        long shipmentId=number(shipment,"id"), warehouseId=number(shipment,"warehouse_id");
        int existing=count("SELECT COUNT(*) FROM picking_tasks WHERE tenant_id=? AND outbound_shipment_id=? AND status<>'CANCELLED'",a.tenantId(),shipmentId);
        if(existing>0) throw new IllegalStateException("An active picking task already exists for this shipment");
        List<Map<String,Object>> allocations=jdbc.queryForList("""
                SELECT a.id allocation_id,a.outbound_shipment_line_id,a.item_id,a.location_id,a.allocated_qty,
                       l.outbound_shipment_id,l.line_number
                FROM outbound_shipment_allocations a
                JOIN outbound_shipment_lines l ON l.id=a.outbound_shipment_line_id AND l.tenant_id=a.tenant_id
                WHERE a.tenant_id=? AND l.outbound_shipment_id=? AND a.status IN ('ALLOCATED','PICKING')
                  AND a.allocated_qty>a.picked_qty
                ORDER BY l.line_number,a.id
                """,a.tenantId(),shipmentId);
        if(allocations.isEmpty()) throw new IllegalStateException("Shipment has no remaining allocated inventory");
        String taskPublicId=UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO picking_tasks(public_id,tenant_id,warehouse_id,outbound_shipment_id,task_number,
                      status,priority,created_by_membership_id,notes)
                    VALUES(?,?,?,?,?,'READY',?,?,?)
                    """,taskPublicId,a.tenantId(),warehouseId,shipmentId,taskNo,priority,a.membershipId(),optional(command.notes(),1000));
        } catch(DuplicateKeyException ex){throw new IllegalStateException("Picking task number already exists",ex);}
        long taskId=requiredLong("SELECT id FROM picking_tasks WHERE tenant_id=? AND public_id=?",a.tenantId(),taskPublicId);
        int lineNo=0;
        for(Map<String,Object> allocation:allocations){
            long allocationId=number(allocation,"allocation_id");
            int inserted=jdbc.update("""
                    INSERT INTO picking_task_lines(tenant_id,picking_task_id,outbound_shipment_id,
                      outbound_shipment_line_id,outbound_shipment_allocation_id,warehouse_id,item_id,
                      source_location_id,line_number,planned_qty,picked_qty,status)
                    VALUES(?,?,?,?,?,?,?,?,?,?,'0.0000','PENDING')
                    """,a.tenantId(),taskId,shipmentId,number(allocation,"outbound_shipment_line_id"),
                    allocationId,warehouseId,number(allocation,"item_id"),number(allocation,"location_id"),
                    ++lineNo,allocation.get("allocated_qty"));
            if(inserted!=1) throw new IllegalStateException("Picking task line could not be created");
            long lineId=requiredLong("SELECT id FROM picking_task_lines WHERE tenant_id=? AND picking_task_id=? AND line_number=?",
                    a.tenantId(),taskId,lineNo);
            jdbc.update("""
                    INSERT INTO picking_task_line_serials(tenant_id,picking_task_line_id,
                      outbound_shipment_line_serial_id,outbound_shipment_allocation_id,item_id,serial_id,status)
                    SELECT tenant_id,?,id,outbound_shipment_allocation_id,item_id,serial_id,'ALLOCATED'
                    FROM outbound_shipment_line_serials
                    WHERE tenant_id=? AND outbound_shipment_allocation_id=? AND status='ALLOCATED'
                    """,lineId,a.tenantId(),allocationId);
        }
        jdbc.update("UPDATE outbound_shipments SET status='PICKING',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND tenant_id=? AND status='RELEASED'",
                shipmentId,a.tenantId());
        audit(a,"PICKING_TASK_CREATED",taskPublicId,Map.of("shipmentPublicId",command.shipmentPublicId(),"lineCount",lineNo));
        return publicTask(task(a.tenantId(),taskPublicId));
    }

    @Transactional
    public Map<String,Object> assign(WmsAuthenticatedPrincipal principal,String taskPublicId,AssignPickingCommand command){
        lockTenant(principal); Access a=access(principal,"WMS_PICKING_ASSIGN");
        Map<String,Object> t=taskForUpdate(a.tenantId(),taskPublicId);
        if(!"READY".equals(t.get("status"))) throw new IllegalStateException("Only ready picking tasks can be assigned");
        String member=required(command==null?null:command.membershipPublicId(),"membership public ID",36);
        long membership=requiredLong("""
                SELECT id FROM tenant_memberships WHERE tenant_id=? AND public_id=? AND status='ACTIVE'
                """,a.tenantId(),member);
        int changed=jdbc.update("""
                UPDATE picking_tasks SET assigned_to_membership_id=?,assigned_at=UTC_TIMESTAMP(6),
                  status='ASSIGNED',version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='READY'
                """,membership,number(t,"id"),a.tenantId());
        if(changed!=1) throw new IllegalStateException("Picking task assignment changed; refresh and retry");
        audit(a,"PICKING_TASK_ASSIGNED",taskPublicId,Map.of("membershipPublicId",member));
        return publicTask(task(a.tenantId(),taskPublicId));
    }

    @Transactional
    public Map<String,Object> start(WmsAuthenticatedPrincipal principal,String taskPublicId){
        lockTenant(principal); Access a=access(principal,"WMS_PICKING_START");
        Map<String,Object> t=taskForUpdate(a.tenantId(),taskPublicId);
        if(!"ASSIGNED".equals(t.get("status"))) throw new IllegalStateException("Only assigned picking tasks can be started");
        if(number(t,"assigned_to_membership_id")!=a.membershipId()) throw new AccessDeniedException("Only the assigned member may start this picking task");
        jdbc.update("""
                UPDATE picking_tasks SET status='IN_PROGRESS',started_at=UTC_TIMESTAMP(6),
                  version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='ASSIGNED'
                """,number(t,"id"),a.tenantId());
        audit(a,"PICKING_TASK_STARTED",taskPublicId,Map.of());
        return publicTask(task(a.tenantId(),taskPublicId));
    }

    @Transactional
    public Map<String,Object> confirm(WmsAuthenticatedPrincipal principal,String taskPublicId,int lineNumber,ConfirmPickingCommand command){
        if(command==null||command.requestId()==null) throw new IllegalArgumentException("Picking request UUID is required");
        BigDecimal qty=quantity(command.quantity());
        lockTenant(principal); Access a=access(principal,"WMS_PICKING_CONFIRM");
        Map<String,Object> t=taskForUpdate(a.tenantId(),taskPublicId);
        if(!"IN_PROGRESS".equals(t.get("status"))) throw new IllegalStateException("Picking task must be in progress");
        if(number(t,"assigned_to_membership_id")!=a.membershipId()) throw new AccessDeniedException("Only the assigned member may confirm picking");
        Map<String,Object> line=requiredRow("""
                SELECT l.*,i.public_id item_public_id,i.tracking_method,
                       src.public_id source_location_public_id,
                       lot.public_id lot_public_id
                FROM picking_task_lines l
                JOIN items i ON i.id=l.item_id AND i.tenant_id=l.tenant_id
                JOIN warehouse_locations src ON src.id=l.source_location_id AND src.tenant_id=l.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id=(SELECT lot_id FROM outbound_shipment_allocations
                    WHERE id=l.outbound_shipment_allocation_id AND tenant_id=l.tenant_id) AND lot.tenant_id=l.tenant_id
                WHERE l.tenant_id=? AND l.picking_task_id=? AND l.line_number=? FOR UPDATE
                """,a.tenantId(),number(t,"id"),lineNumber);
        BigDecimal planned=decimal(line,"planned_qty"),picked=decimal(line,"picked_qty");
        if(picked.add(qty).compareTo(planned)>0) throw new IllegalArgumentException("Picked quantity exceeds allocated quantity");
        long warehouseId=number(line,"warehouse_id"),lineId=number(line,"id"),allocationId=number(line,"outbound_shipment_allocation_id");
        String sourceLocation=string(line,"source_location_public_id"),item=string(line,"item_public_id");
        String lot=nullable(line.get("lot_public_id"));
        List<String> serials=command.serialPublicIds()==null?List.of():command.serialPublicIds().stream().map(x->required(x,"serial public ID",36)).toList();
        boolean serialTracked=Set.of("SERIAL","LOT_AND_SERIAL").contains(string(line,"tracking_method"));
        if(serialTracked){
            if(qty.stripTrailingZeros().scale()>0||qty.compareTo(BigDecimal.valueOf(serials.size()))!=0||serials.isEmpty())
                throw new IllegalArgumentException("Picked serial count must exactly match whole-unit quantity");
        } else if(!serials.isEmpty()) throw new IllegalArgumentException("Serial IDs are only valid for serial-tracked items");
        String staging=required(command.stagingLocationPublicId(),"staging location public ID",36);
        Map<String,Object> destination=requiredRow("""
                SELECT id,location_type,status FROM warehouse_locations
                WHERE tenant_id=? AND warehouse_id=? AND public_id=? FOR UPDATE
                """,a.tenantId(),warehouseId,staging);
        if(!"STAGING".equals(destination.get("location_type"))||!"ACTIVE".equals(destination.get("status")))
            throw new IllegalArgumentException("Destination must be an active STAGING location in the task warehouse");
        if(sourceLocation.equals(staging)) throw new IllegalArgumentException("Picking source and staging location must differ");
        if(serialTracked){
            for(String serial:serials){
                int match=count("""
                        SELECT COUNT(*) FROM picking_task_line_serials ps
                        JOIN inventory_serials s ON s.id=ps.serial_id AND s.tenant_id=ps.tenant_id
                        WHERE ps.tenant_id=? AND ps.picking_task_line_id=? AND ps.status='ALLOCATED'
                          AND s.public_id=?
                        """,a.tenantId(),lineId,serial);
                if(match!=1) throw new IllegalArgumentException("Serial is not allocated to this picking task line");
            }
        }
        try {
            jdbc.update("""
                    INSERT INTO picking_confirmations(public_id,tenant_id,picking_task_line_id,
                      outbound_shipment_allocation_id,item_id,confirmation_type,confirmed_qty,
                      request_id,confirmed_by_membership_id,notes)
                    VALUES(?,?,?,?,?,'PICK',?,?,?,?)
                    """,UUID.randomUUID().toString(),a.tenantId(),lineId,allocationId,number(line,"item_id"),
                    qty,command.requestId().toString(),a.membershipId(),optional(command.notes(),1000));
        } catch(DuplicateKeyException ex){throw new IllegalStateException("Picking request UUID has already been used",ex);}
        reservationService.consume(principal,new WmsInventoryReservationService.ReservationCommand(
                string(t,"warehouse_public_id"),sourceLocation,item,lot,qty,List.of()));
        if(serialTracked){
            for(String serial:serials){
                postTransfer(principal,UUID.randomUUID(),string(t,"warehouse_public_id"),sourceLocation,staging,item,lot,serial,BigDecimal.ONE);
            }
            jdbc.update("""
                    UPDATE picking_task_line_serials ps
                    JOIN inventory_serials s ON s.id=ps.serial_id AND s.tenant_id=ps.tenant_id
                    SET ps.status='PICKED',ps.picked_at=UTC_TIMESTAMP(6)
                    WHERE ps.tenant_id=? AND ps.picking_task_line_id=? AND s.public_id IN (%s)
                    """.formatted(String.join(",",Collections.nCopies(serials.size(),"?"))),prepend(a.tenantId(),lineId,serials.toArray()));
            jdbc.update("""
                    UPDATE outbound_shipment_line_serials sl
                    JOIN picking_task_line_serials ps ON ps.outbound_shipment_line_serial_id=sl.id AND ps.tenant_id=sl.tenant_id
                    JOIN inventory_serials s ON s.id=ps.serial_id AND s.tenant_id=ps.tenant_id
                    SET sl.status='PICKED',sl.picked_at=UTC_TIMESTAMP(6)
                    WHERE ps.tenant_id=? AND ps.picking_task_line_id=? AND s.public_id IN (%s)
                    """.formatted(String.join(",",Collections.nCopies(serials.size(),"?"))),prepend(a.tenantId(),lineId,serials.toArray()));
        } else {
            postTransfer(principal,command.requestId(),string(t,"warehouse_public_id"),sourceLocation,staging,item,lot,null,qty);
        }
        BigDecimal total=picked.add(qty);
        String lineStatus=total.compareTo(planned)==0?"PICKED":"PARTIALLY_PICKED";
        jdbc.update("""
                UPDATE picking_task_lines SET picked_qty=?,status=?,completed_at=CASE WHEN ?='PICKED' THEN UTC_TIMESTAMP(6) ELSE completed_at END
                WHERE id=? AND tenant_id=?
                """,total,lineStatus,lineStatus,lineId,a.tenantId());
        jdbc.update("""
                UPDATE outbound_shipment_allocations SET picked_qty=picked_qty+?,
                  status=CASE WHEN picked_qty+? >= allocated_qty THEN 'PICKED' ELSE 'PICKING' END,
                  updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND picked_qty+?<=allocated_qty
                """,qty,qty,allocationId,a.tenantId(),qty);
        jdbc.update("""
                UPDATE outbound_shipment_lines SET picked_qty=picked_qty+?,
                  status=CASE WHEN picked_qty+? >= allocated_qty THEN 'PICKED' ELSE 'PICKING' END,
                  updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND picked_qty+?<=allocated_qty
                """,qty,qty,number(line,"outbound_shipment_line_id"),a.tenantId(),qty);
        int remaining=count("SELECT COUNT(*) FROM picking_task_lines WHERE tenant_id=? AND picking_task_id=? AND status<>'PICKED'",
                a.tenantId(),number(t,"id"));
        if(remaining==0){
            jdbc.update("UPDATE picking_tasks SET status='COMPLETED',completed_at=UTC_TIMESTAMP(6),version=version+1 WHERE id=? AND tenant_id=?",
                    number(t,"id"),a.tenantId());
            jdbc.update("""
                    UPDATE outbound_shipments SET status='PICKED',picked_at=UTC_TIMESTAMP(6),
                      picked_by_membership_id=?,version=version+1,updated_at=UTC_TIMESTAMP(6)
                    WHERE id=? AND tenant_id=? AND status='PICKING'
                    """,a.membershipId(),number(t,"outbound_shipment_id"),a.tenantId());
        }
        audit(a,"PICKING_CONFIRMED",taskPublicId,Map.of("lineNumber",lineNumber,"confirmedQty",qty,"requestId",command.requestId()));
        return publicTask(task(a.tenantId(),taskPublicId));
    }

    private void postTransfer(WmsAuthenticatedPrincipal principal,UUID requestId,String warehouse,String source,
                              String destination,String item,String lot,String serial,BigDecimal qty){
        WmsInventoryPostingService.PostingLeg from=new WmsInventoryPostingService.PostingLeg(
                warehouse,source,item,lot,serial,null,"AVAILABLE",qty);
        WmsInventoryPostingService.PostingLeg to=new WmsInventoryPostingService.PostingLeg(
                warehouse,destination,item,lot,serial,null,"AVAILABLE",qty);
        postingService.post(principal,new WmsInventoryPostingService.PostingCommand(requestId,"TRANSFER",List.of(from,to),"Outbound picking to staging"));
    }

    private Map<String,Object> task(long tenant,String publicId){
        return requiredRow("""
                SELECT t.*,w.public_id warehouse_public_id,s.public_id shipment_public_id,
                       s.document_number shipment_document_number
                FROM picking_tasks t JOIN warehouses w ON w.id=t.warehouse_id AND w.tenant_id=t.tenant_id
                JOIN outbound_shipments s ON s.id=t.outbound_shipment_id AND s.tenant_id=t.tenant_id
                WHERE t.tenant_id=? AND t.public_id=?
                """,tenant,required(publicId,"task public ID",36));
    }
    private Map<String,Object> taskForUpdate(long tenant,String id){
        return requiredRow("""
                SELECT t.*,w.public_id warehouse_public_id FROM picking_tasks t
                JOIN warehouses w ON w.id=t.warehouse_id AND w.tenant_id=t.tenant_id
                WHERE t.tenant_id=? AND t.public_id=? FOR UPDATE
                """,tenant,required(id,"task public ID",36));
    }
    private Map<String,Object> publicTask(Map<String,Object> row){
        Map<String,Object> out=new LinkedHashMap<>(row);out.remove("id");out.remove("warehouse_id");out.remove("outbound_shipment_id");out.remove("picking_wave_id");out.remove("created_by_membership_id");return out;
    }
    private void lockTenant(WmsAuthenticatedPrincipal p){
        if(p==null)throw new AccessDeniedException("Tenant context is required");
        if(jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE",Long.class,p.tenantId()).isEmpty())
            throw new AccessDeniedException("Active tenant is unavailable");
    }
    private Access access(WmsAuthenticatedPrincipal p,String permission){
        if(p==null)throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess x=accessService.resolve(p.userPublicId(),p.membershipPublicId());
        if(!Objects.equals(x.userPublicId(),p.userPublicId())||!Objects.equals(x.tenantId(),p.tenantId())
                ||!Objects.equals(x.tenantPublicId(),p.tenantPublicId())||!Objects.equals(x.membershipId(),p.membershipId())
                ||!Objects.equals(x.membershipPublicId(),p.membershipPublicId()))throw new AccessDeniedException("Tenant context is unavailable");
        if(!x.hasPermission(permission))throw new AccessDeniedException("Picking permission is required");
        return new Access(x.tenantId(),x.membershipId(),x.userPublicId());
    }
    private void audit(Access a,String type,String subject,Map<String,Object> payload){
        final String json;try{json=mapper.writeValueAsString(payload);}catch(JsonProcessingException e){throw new IllegalStateException("Picking audit payload serialization failed",e);}
        int rows=jdbc.update("""
                INSERT INTO tenant_audit_events(public_id,tenant_id,actor_membership_id,actor_type,operation_ref,event_type,subject_public_id,change_payload)
                SELECT ?,t.id,m.id,'TENANT_MEMBER',?,?,?,? FROM tenants t
                JOIN tenant_memberships m ON m.id=? AND m.tenant_id=t.id AND m.status='ACTIVE'
                JOIN users u ON u.id=m.user_id AND u.public_id=? AND u.status='ACTIVE'
                WHERE t.id=? AND t.status='ACTIVE'
                """,UUID.randomUUID().toString(),UUID.randomUUID().toString(),type,subject,json,a.membershipId(),a.userPublicId(),a.tenantId());
        if(rows!=1)throw new AccessDeniedException("Audit actor is no longer active");
    }
    private Map<String,Object> requiredRow(String sql,Object...args){List<Map<String,Object>>r=jdbc.queryForList(sql,args);if(r.isEmpty())throw new IllegalArgumentException("Referenced picking record was not found");return r.getFirst();}
    private long requiredLong(String sql,Object...args){List<Long>r=jdbc.queryForList(sql,Long.class,args);if(r.isEmpty())throw new IllegalArgumentException("Referenced record was not found");return r.getFirst();}
    private int count(String sql,Object...args){Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;}
    private static long number(Map<String,Object>m,String k){Object v=m.get(k);if(v instanceof Number n)return n.longValue();throw new IllegalStateException("Missing numeric field "+k);}
    private static BigDecimal decimal(Map<String,Object>m,String k){Object v=m.get(k);if(v instanceof BigDecimal d)return d;throw new IllegalStateException("Missing decimal field "+k);}
    private static String string(Map<String,Object>m,String k){Object v=m.get(k);if(v instanceof String s)return s;throw new IllegalStateException("Missing text field "+k);}
    private static String nullable(Object v){return v==null?null:String.valueOf(v);}
    private static BigDecimal quantity(BigDecimal v){if(v==null||v.signum()<=0)throw new IllegalArgumentException("Picked quantity must be positive");try{return v.setScale(4,RoundingMode.UNNECESSARY);}catch(ArithmeticException e){throw new IllegalArgumentException("Quantity supports at most four decimals",e);}}
    private static String required(String v,String label,int max){if(v==null||v.isBlank()||v.trim().length()>max)throw new IllegalArgumentException(label+" is required and must not exceed "+max+" characters");return v.trim();}
    private static String optional(String v,int max){if(v==null||v.isBlank())return null;if(v.trim().length()>max)throw new IllegalArgumentException("Text exceeds maximum length");return v.trim();}
    private static String enumValue(String v,Set<String>s){String n=required(v,"priority",20).toUpperCase(Locale.ROOT);if(!s.contains(n))throw new IllegalArgumentException("Priority is invalid");return n;}
    private static Object[] prepend(long tenant,long line,Object[] values){Object[]r=new Object[values.length+2];r[0]=tenant;r[1]=line;System.arraycopy(values,0,r,2,values.length);return r;}
    private record Access(long tenantId,long membershipId,String userPublicId){}
    public record CreatePickingCommand(String shipmentPublicId,String taskNumber,String priority,String notes){}
    public record AssignPickingCommand(String membershipPublicId){}
    public record ConfirmPickingCommand(UUID requestId,BigDecimal quantity,String stagingLocationPublicId,List<String>serialPublicIds,String notes){}
}
