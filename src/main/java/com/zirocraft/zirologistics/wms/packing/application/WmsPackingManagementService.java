package com.zirocraft.zirologistics.wms.packing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
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

/** Tenant-scoped packing workflow. Packing records never mutate inventory; shipping owns final ISSUE. */
@Service
@Profile("wms")
public class WmsPackingManagementService {
    private static final Set<String> PACKAGE_TYPES = Set.of("CARTON","PALLET","CRATE","ENVELOPE","BAG","OTHER");
    private final WmsMembershipAccessService accessService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsPackingManagementService(WmsMembershipAccessService accessService, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService=Objects.requireNonNull(accessService);
        this.jdbc=Objects.requireNonNull(jdbc);
        this.mapper=Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(WmsAuthenticatedPrincipal principal) {
        Access a=access(principal,"WMS_PACKING_READ");
        return jdbc.queryForList("""
                SELECT ps.public_id,ps.packing_number,ps.status,ps.started_at,ps.completed_at,
                       s.public_id shipment_public_id,s.document_number,w.public_id warehouse_public_id,
                       m.public_id assigned_to_membership_public_id,ps.created_at
                FROM packing_sessions ps
                JOIN outbound_shipments s ON s.id=ps.outbound_shipment_id AND s.tenant_id=ps.tenant_id
                JOIN warehouses w ON w.id=ps.warehouse_id AND w.tenant_id=ps.tenant_id
                LEFT JOIN tenant_memberships m ON m.id=ps.assigned_to_membership_id AND m.tenant_id=ps.tenant_id
                WHERE ps.tenant_id=? ORDER BY ps.created_at DESC,ps.id DESC LIMIT 200
                """,a.tenantId());
    }

    @Transactional(readOnly=true)
    public Map<String,Object> get(WmsAuthenticatedPrincipal principal,String sessionPublicId) {
        Access a=access(principal,"WMS_PACKING_READ");
        Map<String,Object> session=session(a.tenantId(),sessionPublicId);
        Map<String,Object> result=publicSession(session);
        List<Map<String,Object>> packages=jdbc.queryForList("""
                SELECT p.public_id,p.package_number,p.package_type,p.status,p.weight,p.length,p.width,p.height,
                       p.sealed_at,p.created_at
                FROM packing_packages p WHERE p.tenant_id=? AND p.packing_session_id=?
                ORDER BY p.id
                """,a.tenantId(),number(session,"id"));
        for(Map<String,Object> pack:packages) {
            long packageId=requiredLong("SELECT id FROM packing_packages WHERE tenant_id=? AND public_id=?",
                    a.tenantId(),pack.get("public_id"));
            pack.put("lines",jdbc.queryForList("""
                    SELECT pl.line_number,pl.packed_qty,pl.notes,
                           i.public_id item_public_id,i.sku,i.item_name,
                           ptl.line_number picking_line_number
                    FROM packing_package_lines pl
                    JOIN items i ON i.id=pl.item_id AND i.tenant_id=pl.tenant_id
                    JOIN picking_task_lines ptl ON ptl.id=pl.picking_task_line_id AND ptl.tenant_id=pl.tenant_id
                    WHERE pl.tenant_id=? AND pl.packing_package_id=? ORDER BY pl.line_number
                    """,a.tenantId(),packageId));
            pack.remove("id");
        }
        result.put("packages",packages);
        return result;
    }

    @Transactional
    public Map<String,Object> create(WmsAuthenticatedPrincipal principal,CreateSessionCommand command) {
        if(command==null) throw new IllegalArgumentException("Packing session request is required");
        lockTenant(principal);
        Access a=access(principal,"WMS_PACKING_CREATE");
        String shipmentPublicId=required(command.shipmentPublicId(),"shipment public ID",36);
        Map<String,Object> shipment=requiredRow("""
                SELECT id,warehouse_id,status,public_id,document_number
                FROM outbound_shipments WHERE tenant_id=? AND public_id=? FOR UPDATE
                """,a.tenantId(),shipmentPublicId);
        if(!"PICKED".equals(shipment.get("status")))
            throw new IllegalStateException("Packing can only start after the shipment is fully picked");
        if(count("SELECT COUNT(*) FROM packing_sessions WHERE tenant_id=? AND outbound_shipment_id=? AND status<>'CANCELLED'",
                a.tenantId(),number(shipment,"id"))>0)
            throw new IllegalStateException("An active packing session already exists for this shipment");
        if(count("""
                SELECT COUNT(*) FROM picking_task_lines WHERE tenant_id=? AND outbound_shipment_id=?
                  AND picked_qty>0
                """,a.tenantId(),number(shipment,"id"))==0)
            throw new IllegalStateException("Shipment has no picked quantities to pack");
        String publicId=UUID.randomUUID().toString();
        String packingNumber=required(command.packingNumber(),"packing number",60).toUpperCase(Locale.ROOT);
        try {
            jdbc.update("""
                    INSERT INTO packing_sessions(public_id,tenant_id,outbound_shipment_id,warehouse_id,
                      packing_number,status,created_by_membership_id,notes)
                    VALUES(?,?,?,?,?,'DRAFT',?,?)
                    """,publicId,a.tenantId(),number(shipment,"id"),number(shipment,"warehouse_id"),
                    packingNumber,a.membershipId(),optional(command.notes(),1000));
        } catch(DuplicateKeyException ex) { throw new IllegalStateException("Packing number already exists",ex); }
        audit(a,"PACKING_SESSION_CREATED",publicId,Map.of("shipmentPublicId",shipmentPublicId));
        return publicSession(session(a.tenantId(),publicId));
    }

    @Transactional
    public Map<String,Object> ready(WmsAuthenticatedPrincipal principal,String sessionPublicId) {
        lockTenant(principal); Access a=access(principal,"WMS_PACKING_READY");
        Map<String,Object> s=sessionForUpdate(a.tenantId(),sessionPublicId);
        if(!"DRAFT".equals(s.get("status"))) throw new IllegalStateException("Only draft packing sessions can be readied");
        if(!"PICKED".equals(s.get("shipment_status"))) throw new IllegalStateException("Shipment must remain fully picked");
        int lines=count("SELECT COUNT(*) FROM picking_task_lines WHERE tenant_id=? AND outbound_shipment_id=? AND picked_qty>0",
                a.tenantId(),number(s,"outbound_shipment_id"));
        if(lines==0) throw new IllegalStateException("Shipment has no picked quantities to pack");
        jdbc.update("UPDATE packing_sessions SET status='READY',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND tenant_id=? AND status='DRAFT'",
                number(s,"id"),a.tenantId());
        audit(a,"PACKING_SESSION_READY",sessionPublicId,Map.of());
        return publicSession(session(a.tenantId(),sessionPublicId));
    }

    @Transactional
    public Map<String,Object> assign(WmsAuthenticatedPrincipal principal,String sessionPublicId,AssignSessionCommand command) {
        lockTenant(principal); Access a=access(principal,"WMS_PACKING_ASSIGN");
        Map<String,Object> s=sessionForUpdate(a.tenantId(),sessionPublicId);
        if(!"READY".equals(s.get("status"))) throw new IllegalStateException("Only ready packing sessions can be assigned");
        String member=required(command==null?null:command.membershipPublicId(),"membership public ID",36);
        long membership=requiredLong("""
                SELECT id FROM tenant_memberships WHERE tenant_id=? AND public_id=? AND status='ACTIVE'
                """,a.tenantId(),member);
        jdbc.update("""
                UPDATE packing_sessions SET assigned_to_membership_id=?,status='READY',
                  version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='READY'
                """,membership,number(s,"id"),a.tenantId());
        audit(a,"PACKING_SESSION_ASSIGNED",sessionPublicId,Map.of("membershipPublicId",member));
        return publicSession(session(a.tenantId(),sessionPublicId));
    }

    @Transactional
    public Map<String,Object> start(WmsAuthenticatedPrincipal principal,String sessionPublicId) {
        lockTenant(principal); Access a=access(principal,"WMS_PACKING_START");
        Map<String,Object> s=sessionForUpdate(a.tenantId(),sessionPublicId);
        if(!"READY".equals(s.get("status"))) throw new IllegalStateException("Only ready packing sessions can be started");
        if(s.get("assigned_to_membership_id")==null || number(s,"assigned_to_membership_id")!=a.membershipId())
            throw new AccessDeniedException("Only the assigned member may start this packing session");
        jdbc.update("""
                UPDATE packing_sessions SET status='IN_PROGRESS',started_at=UTC_TIMESTAMP(6),
                  version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='READY'
                """,number(s,"id"),a.tenantId());
        audit(a,"PACKING_SESSION_STARTED",sessionPublicId,Map.of());
        return publicSession(session(a.tenantId(),sessionPublicId));
    }

    @Transactional
    public Map<String,Object> createPackage(WmsAuthenticatedPrincipal principal,String sessionPublicId,CreatePackageCommand command) {
        if(command==null) throw new IllegalArgumentException("Package request is required");
        lockTenant(principal); Access a=access(principal,"WMS_PACKING_PACKAGE");
        Map<String,Object> s=sessionForUpdate(a.tenantId(),sessionPublicId);
        requireAssignedInProgress(s,a);
        String type=enumValue(command.packageType()==null?"CARTON":command.packageType(),PACKAGE_TYPES,"package type");
        BigDecimal weight=optionalMeasure(command.weight(),4,"weight");
        if(command.weightUomCode()!=null&&!command.weightUomCode().isBlank()&&weight==null)
            throw new IllegalArgumentException("Weight UOM cannot be supplied without a package weight");
        BigDecimal length=optionalMeasure(command.length(),3,"length");
        BigDecimal width=optionalMeasure(command.width(),3,"width");
        BigDecimal height=optionalMeasure(command.height(),3,"height");
        String publicId=UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO packing_packages(public_id,tenant_id,packing_session_id,outbound_shipment_id,
                      warehouse_id,package_number,package_type,status,weight,length,width,height,weight_uom_id)
                    VALUES(?,?,?,?,?,?,?,'OPEN',?,?,?,?,?)
                    """,publicId,a.tenantId(),number(s,"id"),number(s,"outbound_shipment_id"),number(s,"warehouse_id"),
                    required(command.packageNumber(),"package number",60),type,weight,length,width,height,
                    resolveWeightUom(command.weightUomCode()));
        } catch(DuplicateKeyException ex) { throw new IllegalStateException("Package number already exists in this session",ex); }
        audit(a,"PACKING_PACKAGE_CREATED",publicId,Map.of("sessionPublicId",sessionPublicId,"packageType",type));
        return packageRow(a.tenantId(),publicId);
    }

    @Transactional
    public Map<String,Object> addLine(WmsAuthenticatedPrincipal principal,String sessionPublicId,String packagePublicId,
                                      AddPackageLineCommand command) {
        if(command==null||command.requestId()==null) throw new IllegalArgumentException("Packing request UUID is required");
        BigDecimal qty=quantity(command.quantity());
        lockTenant(principal); Access a=access(principal,"WMS_PACKING_PACKAGE");
        Map<String,Object> s=sessionForUpdate(a.tenantId(),sessionPublicId); requireAssignedInProgress(s,a);
        Map<String,Object> p=requiredRow("""
                SELECT * FROM packing_packages WHERE tenant_id=? AND packing_session_id=? AND public_id=? FOR UPDATE
                """,a.tenantId(),number(s,"id"),required(packagePublicId,"package public ID",36));
        if(!"OPEN".equals(p.get("status"))) throw new IllegalStateException("Only open packages can be edited");
        int pickingLineNumber=command.pickingLineNumber();
        Map<String,Object> line=requiredRow("""
                SELECT ptl.*,i.public_id item_public_id,i.tracking_method,
                       a.id allocation_id
                FROM picking_task_lines ptl
                JOIN items i ON i.id=ptl.item_id AND i.tenant_id=ptl.tenant_id
                JOIN outbound_shipment_allocations a ON a.id=ptl.outbound_shipment_allocation_id AND a.tenant_id=ptl.tenant_id
                WHERE ptl.tenant_id=? AND ptl.outbound_shipment_id=? AND ptl.line_number=?
                  AND ptl.picked_qty>0
                ORDER BY ptl.id LIMIT 1 FOR UPDATE
                """,a.tenantId(),number(s,"outbound_shipment_id"),pickingLineNumber);
        long pickingLineId=number(line,"id");
        BigDecimal already=jdbc.queryForObject("""
                SELECT COALESCE(SUM(packed_qty),0.0000) FROM packing_package_lines
                WHERE tenant_id=? AND picking_task_line_id=?
                """,BigDecimal.class,a.tenantId(),pickingLineId);
        if(already.add(qty).compareTo(decimal(line,"picked_qty"))>0)
            throw new IllegalArgumentException("Packed quantity exceeds the picked quantity");
        List<String> serialPublicIds=command.serialPublicIds()==null?List.of():
                command.serialPublicIds().stream().map(x->required(x,"serial public ID",36)).toList();
        boolean serialTracked=Set.of("SERIAL","LOT_AND_SERIAL").contains(line.get("tracking_method"));
        List<Map<String,Object>> serialRows=List.of();
        if(serialTracked) {
            if(qty.stripTrailingZeros().scale()>0||qty.compareTo(BigDecimal.valueOf(serialPublicIds.size()))!=0||serialPublicIds.isEmpty())
                throw new IllegalArgumentException("Packed serial count must exactly match whole-unit quantity");
            String placeholders=String.join(",",Collections.nCopies(serialPublicIds.size(),"?"));
            List<Object> args=new ArrayList<>(List.of(a.tenantId(),pickingLineId)); args.addAll(serialPublicIds);
            serialRows=jdbc.queryForList("""
                    SELECT ps.id,ps.serial_id,ps.item_id,ps.outbound_shipment_allocation_id,si.public_id
                    FROM picking_task_line_serials ps
                    JOIN inventory_serials si ON si.id=ps.serial_id AND si.tenant_id=ps.tenant_id
                    WHERE ps.tenant_id=? AND ps.picking_task_line_id=? AND ps.status='PICKED'
                      AND si.public_id IN (%s)
                    ORDER BY ps.id FOR UPDATE
                    """.formatted(placeholders),args.toArray());
            if(serialRows.size()!=serialPublicIds.size()) throw new IllegalArgumentException("Every serial must belong to this picked line");
            if(count("SELECT COUNT(*) FROM packing_package_serials WHERE tenant_id=? AND picking_task_line_id=? AND picking_task_line_serial_id IN (SELECT id FROM picking_task_line_serials WHERE tenant_id=? AND picking_task_line_id=? AND serial_id IN (SELECT id FROM inventory_serials WHERE tenant_id=? AND public_id IN ("+placeholders+")))",
                    buildArgs(a.tenantId(),pickingLineId,a.tenantId(),pickingLineId,a.tenantId(),serialPublicIds))>0)
                throw new IllegalStateException("A serial has already been packed");
        } else if(!serialPublicIds.isEmpty()) throw new IllegalArgumentException("Serial IDs are only valid for serial-tracked items");
        int lineNo=count("SELECT COUNT(*) FROM packing_package_lines WHERE tenant_id=? AND packing_package_id=?",
                a.tenantId(),number(p,"id"))+1;
        int inserted=jdbc.update("""
                INSERT INTO packing_package_lines(tenant_id,packing_package_id,packing_session_id,outbound_shipment_id,
                  warehouse_id,picking_task_line_id,outbound_shipment_allocation_id,item_id,line_number,packed_qty,notes)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,a.tenantId(),number(p,"id"),number(s,"id"),number(s,"outbound_shipment_id"),number(s,"warehouse_id"),
                pickingLineId,number(line,"allocation_id"),number(line,"item_id"),lineNo,qty,optional(command.notes(),1000));
        if(inserted!=1) throw new IllegalStateException("Package line could not be created");
        long packageLineId=requiredLong("SELECT id FROM packing_package_lines WHERE tenant_id=? AND packing_package_id=? AND line_number=?",
                a.tenantId(),number(p,"id"),lineNo);
        for(Map<String,Object> serial:serialRows) {
            jdbc.update("""
                    INSERT INTO packing_package_serials(tenant_id,packing_package_line_id,packing_package_id,
                      packing_session_id,outbound_shipment_id,warehouse_id,picking_task_line_serial_id,
                      picking_task_line_id,outbound_shipment_allocation_id,item_id,serial_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """,a.tenantId(),packageLineId,number(p,"id"),number(s,"id"),number(s,"outbound_shipment_id"),
                    number(s,"warehouse_id"),number(serial,"id"),pickingLineId,number(serial,"outbound_shipment_allocation_id"),
                    number(serial,"item_id"),number(serial,"serial_id"));
        }
        try {
            jdbc.update("""
                    INSERT INTO packing_confirmations(public_id,tenant_id,packing_package_line_id,packing_package_id,
                      packing_session_id,outbound_shipment_id,warehouse_id,outbound_shipment_allocation_id,item_id,
                      confirmation_type,confirmed_qty,request_id,confirmed_by_membership_id,notes)
                    VALUES(?,?,?,?,?,?,?,?,?,'PACK',?,?,?,?)
                    """,UUID.randomUUID().toString(),a.tenantId(),packageLineId,number(p,"id"),number(s,"id"),
                    number(s,"outbound_shipment_id"),number(s,"warehouse_id"),number(line,"allocation_id"),
                    number(line,"item_id"),qty,command.requestId().toString(),a.membershipId(),optional(command.notes(),1000));
        } catch(DuplicateKeyException ex) { throw new IllegalStateException("Packing request UUID has already been used",ex); }
        audit(a,"PACKING_PACKAGE_LINE_ADDED",packagePublicId,Map.of("sessionPublicId",sessionPublicId,
                "pickingLineNumber",pickingLineNumber,"packedQty",qty,"requestId",command.requestId()));
        return packageRow(a.tenantId(),packagePublicId);
    }

    @Transactional
    public Map<String,Object> sealPackage(WmsAuthenticatedPrincipal principal,String sessionPublicId,String packagePublicId) {
        lockTenant(principal); Access a=access(principal,"WMS_PACKING_SEAL");
        Map<String,Object> s=sessionForUpdate(a.tenantId(),sessionPublicId); requireAssignedInProgress(s,a);
        Map<String,Object> p=requiredRow("""
                SELECT * FROM packing_packages WHERE tenant_id=? AND packing_session_id=? AND public_id=? FOR UPDATE
                """,a.tenantId(),number(s,"id"),required(packagePublicId,"package public ID",36));
        if(!"OPEN".equals(p.get("status"))) throw new IllegalStateException("Only open packages can be sealed");
        if(count("SELECT COUNT(*) FROM packing_package_lines WHERE tenant_id=? AND packing_package_id=?",
                a.tenantId(),number(p,"id"))==0) throw new IllegalStateException("An empty package cannot be sealed");
        int badSerialLines=count("""
                SELECT COUNT(*) FROM packing_package_lines pl
                JOIN items i ON i.id=pl.item_id AND i.tenant_id=pl.tenant_id
                WHERE pl.tenant_id=? AND pl.packing_package_id=?
                  AND i.tracking_method IN ('SERIAL','LOT_AND_SERIAL')
                  AND pl.packed_qty<>(SELECT COUNT(*) FROM packing_package_serials ps
                    WHERE ps.tenant_id=pl.tenant_id AND ps.packing_package_line_id=pl.id)
                """,a.tenantId(),number(p,"id"));
        if(badSerialLines>0) throw new IllegalStateException("Package serial contents do not reconcile");
        jdbc.update("UPDATE packing_packages SET status='SEALED',sealed_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE id=? AND tenant_id=? AND status='OPEN'",
                number(p,"id"),a.tenantId());
        audit(a,"PACKING_PACKAGE_SEALED",packagePublicId,Map.of("sessionPublicId",sessionPublicId));
        return packageRow(a.tenantId(),packagePublicId);
    }

    @Transactional
    public Map<String,Object> complete(WmsAuthenticatedPrincipal principal,String sessionPublicId) {
        lockTenant(principal); Access a=access(principal,"WMS_PACKING_COMPLETE");
        Map<String,Object> s=sessionForUpdate(a.tenantId(),sessionPublicId); requireAssignedInProgress(s,a);
        if(count("SELECT COUNT(*) FROM packing_packages WHERE tenant_id=? AND packing_session_id=?",
                a.tenantId(),number(s,"id"))==0) throw new IllegalStateException("Packing session has no packages");
        if(count("SELECT COUNT(*) FROM packing_packages WHERE tenant_id=? AND packing_session_id=? AND status<>'SEALED'",
                a.tenantId(),number(s,"id"))>0) throw new IllegalStateException("Every package must be sealed before completion");
        int mismatches=count("""
                SELECT COUNT(*) FROM picking_task_lines ptl
                LEFT JOIN (SELECT picking_task_line_id,SUM(packed_qty) packed_qty
                           FROM packing_package_lines WHERE tenant_id=? GROUP BY picking_task_line_id) packed
                  ON packed.picking_task_line_id=ptl.id
                WHERE ptl.tenant_id=? AND ptl.outbound_shipment_id=? AND ptl.picked_qty>0
                  AND COALESCE(packed.packed_qty,0.0000)<>ptl.picked_qty
                """,a.tenantId(),a.tenantId(),number(s,"outbound_shipment_id"));
        if(mismatches>0) throw new IllegalStateException("Packed quantities must exactly reconcile with picked quantities");
        if(!"PICKED".equals(s.get("shipment_status")))
            throw new IllegalStateException("Shipment must remain PICKED until packing completes");
        int shipmentChanged=jdbc.update("""
                UPDATE outbound_shipments SET status='PACKED',packed_at=UTC_TIMESTAMP(6),
                  version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='PICKED'
                """,number(s,"outbound_shipment_id"),a.tenantId());
        if(shipmentChanged!=1) throw new IllegalStateException("Shipment state changed while packing; refresh and retry");
        int sessionChanged=jdbc.update("""
                UPDATE packing_sessions SET status='COMPLETED',completed_at=UTC_TIMESTAMP(6),
                  version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='IN_PROGRESS'
                """,number(s,"id"),a.tenantId());
        if(sessionChanged!=1) throw new IllegalStateException("Packing session state changed; refresh and retry");
        audit(a,"PACKING_SESSION_COMPLETED",sessionPublicId,Map.of("packageCount",
                count("SELECT COUNT(*) FROM packing_packages WHERE tenant_id=? AND packing_session_id=?",a.tenantId(),number(s,"id"))));
        return publicSession(session(a.tenantId(),sessionPublicId));
    }

    private Map<String,Object> packageRow(long tenant,String publicId) {
        return requiredRow("""
                SELECT p.public_id,p.package_number,p.package_type,p.status,p.weight,p.length,p.width,p.height,
                       p.sealed_at,p.created_at FROM packing_packages p
                WHERE p.tenant_id=? AND p.public_id=?
                """,tenant,publicId);
    }
    private Map<String,Object> session(long tenant,String publicId) {
        return requiredRow("""
                SELECT ps.*,s.status shipment_status,s.public_id shipment_public_id,s.document_number,
                       w.public_id warehouse_public_id
                FROM packing_sessions ps
                JOIN outbound_shipments s ON s.id=ps.outbound_shipment_id AND s.tenant_id=ps.tenant_id
                JOIN warehouses w ON w.id=ps.warehouse_id AND w.tenant_id=ps.tenant_id
                WHERE ps.tenant_id=? AND ps.public_id=?
                """,tenant,required(publicId,"packing session public ID",36));
    }
    private Map<String,Object> sessionForUpdate(long tenant,String publicId) {
        return requiredRow("""
                SELECT ps.*,s.status shipment_status,s.public_id shipment_public_id,s.document_number,
                       w.public_id warehouse_public_id
                FROM packing_sessions ps
                JOIN outbound_shipments s ON s.id=ps.outbound_shipment_id AND s.tenant_id=ps.tenant_id
                JOIN warehouses w ON w.id=ps.warehouse_id AND w.tenant_id=ps.tenant_id
                WHERE ps.tenant_id=? AND ps.public_id=? FOR UPDATE
                """,tenant,required(publicId,"packing session public ID",36));
    }
    private Map<String,Object> publicSession(Map<String,Object> row) {
        Map<String,Object> out=new LinkedHashMap<>(row);
        out.remove("id");out.remove("warehouse_id");out.remove("outbound_shipment_id");
        out.remove("created_by_membership_id");out.remove("assigned_to_membership_id");
        return out;
    }
    private void requireAssignedInProgress(Map<String,Object> s,Access a) {
        if(!"IN_PROGRESS".equals(s.get("status"))) throw new IllegalStateException("Packing session must be in progress");
        if(s.get("assigned_to_membership_id")==null||number(s,"assigned_to_membership_id")!=a.membershipId())
            throw new AccessDeniedException("Only the assigned member may operate this packing session");
    }
    private Long resolveWeightUom(String code) {
        if(code==null||code.isBlank()) return null;
        return requiredLong("SELECT id FROM units_of_measure WHERE uom_code=? AND dimension='MASS' AND is_active=1",code.trim().toUpperCase(Locale.ROOT));
    }
    private void lockTenant(WmsAuthenticatedPrincipal p) {
        if(p==null) throw new AccessDeniedException("Tenant context is required");
        if(jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE",Long.class,p.tenantId()).isEmpty())
            throw new AccessDeniedException("Active tenant is unavailable");
    }
    private Access access(WmsAuthenticatedPrincipal p,String permission) {
        if(p==null) throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess x=accessService.resolve(p.userPublicId(),p.membershipPublicId());
        if(!Objects.equals(x.userPublicId(),p.userPublicId())||!Objects.equals(x.tenantId(),p.tenantId())
                ||!Objects.equals(x.tenantPublicId(),p.tenantPublicId())||!Objects.equals(x.membershipId(),p.membershipId())
                ||!Objects.equals(x.membershipPublicId(),p.membershipPublicId()))
            throw new AccessDeniedException("Tenant context is unavailable");
        if(!x.hasPermission(permission)) throw new AccessDeniedException("Packing permission is required");
        return new Access(x.tenantId(),x.membershipId(),x.userPublicId());
    }
    private void audit(Access a,String type,String subject,Map<String,Object> payload) {
        final String json;try{json=mapper.writeValueAsString(payload);}catch(JsonProcessingException e){throw new IllegalStateException("Packing audit payload serialization failed",e);}
        int rows=jdbc.update("""
                INSERT INTO tenant_audit_events(public_id,tenant_id,actor_membership_id,actor_type,operation_ref,event_type,subject_public_id,change_payload)
                SELECT ?,t.id,m.id,'TENANT_MEMBER',?,?,?,? FROM tenants t
                JOIN tenant_memberships m ON m.id=? AND m.tenant_id=t.id AND m.status='ACTIVE'
                JOIN users u ON u.id=m.user_id AND u.public_id=? AND u.status='ACTIVE'
                WHERE t.id=? AND t.status='ACTIVE'
                """,UUID.randomUUID().toString(),UUID.randomUUID().toString(),type,subject,json,a.membershipId(),a.userPublicId(),a.tenantId());
        if(rows!=1) throw new AccessDeniedException("Audit actor is no longer active");
    }
    private Map<String,Object> requiredRow(String sql,Object...args) {
        List<Map<String,Object>> rows=jdbc.queryForList(sql,args);
        if(rows.isEmpty()) throw new IllegalArgumentException("Referenced packing record was not found");
        return rows.getFirst();
    }
    private long requiredLong(String sql,Object...args) {
        List<Long> rows=jdbc.queryForList(sql,Long.class,args);
        if(rows.isEmpty()) throw new IllegalArgumentException("Referenced record was not found");
        return rows.getFirst();
    }
    private int count(String sql,Object...args) {
        Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;
    }
    private static Object[] buildArgs(Object... values) {
        List<Object> out=new ArrayList<>();
        for(Object v:values) {
            if(v instanceof List<?> l) out.addAll(l); else out.add(v);
        }
        return out.toArray();
    }
    private static long number(Map<String,Object> m,String k) {
        Object v=m.get(k);if(v instanceof Number n)return n.longValue();
        throw new IllegalStateException("Missing numeric field "+k);
    }
    private static BigDecimal decimal(Map<String,Object> m,String k) {
        Object v=m.get(k);if(v instanceof BigDecimal d)return d;throw new IllegalStateException("Missing decimal field "+k);
    }
    private static String required(String v,String label,int max) {
        if(v==null||v.isBlank()||v.trim().length()>max) throw new IllegalArgumentException(label+" is required and must not exceed "+max+" characters");
        return v.trim();
    }
    private static String optional(String v,int max) {
        if(v==null||v.isBlank())return null;if(v.trim().length()>max)throw new IllegalArgumentException("Text exceeds maximum length");return v.trim();
    }
    private static String enumValue(String v,Set<String> allowed,String label) {
        String n=required(v,label,30).toUpperCase(Locale.ROOT);if(!allowed.contains(n))throw new IllegalArgumentException(label+" is invalid");return n;
    }
    private static BigDecimal quantity(BigDecimal v) {
        if(v==null||v.signum()<=0)throw new IllegalArgumentException("Packed quantity must be positive");
        try{v=v.setScale(4,RoundingMode.UNNECESSARY);}catch(ArithmeticException e){throw new IllegalArgumentException("Quantity supports at most four decimals",e);}
        if(v.precision()>19)throw new IllegalArgumentException("Quantity exceeds DECIMAL(19,4)");
        return v;
    }
    private static BigDecimal optionalMeasure(BigDecimal v,int scale,String label) {
        if(v==null)return null;if(v.signum()<=0)throw new IllegalArgumentException(label+" must be positive");
        try{v=v.setScale(scale,RoundingMode.UNNECESSARY);}catch(ArithmeticException e){throw new IllegalArgumentException(label+" precision is invalid",e);}
        if(v.precision()>(scale==4?19:12))throw new IllegalArgumentException(label+" exceeds its database precision");
        return v;
    }
    private static record Access(long tenantId,long membershipId,String userPublicId) {}
    public record CreateSessionCommand(String shipmentPublicId,String packingNumber,String notes) {}
    public record AssignSessionCommand(String membershipPublicId) {}
    public record CreatePackageCommand(String packageNumber,String packageType,BigDecimal weight,
                                       BigDecimal length,BigDecimal width,BigDecimal height,String weightUomCode) {}
    public record AddPackageLineCommand(UUID requestId,int pickingLineNumber,BigDecimal quantity,
                                        List<String> serialPublicIds,String notes) {}
}
