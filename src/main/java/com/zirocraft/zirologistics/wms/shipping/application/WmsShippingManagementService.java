package com.zirocraft.zirologistics.wms.shipping.application;

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
 * Tenant-scoped outbound shipping. Final ISSUE, shipment/line/allocation state,
 * serial lifecycle and audit are committed in one transaction.
 */
@Service
@Profile("wms")
public class WmsShippingManagementService {
    private static final int MAX_POSTING_LEGS = 200;

    private final WmsMembershipAccessService accessService;
    private final WmsInventoryPostingService postingService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsShippingManagementService(WmsMembershipAccessService accessService,
                                        WmsInventoryPostingService postingService,
                                        JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.postingService = Objects.requireNonNull(postingService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(WmsAuthenticatedPrincipal principal) {
        Access a = access(principal, "WMS_SHIPPING_READ");
        return jdbc.queryForList("""
                SELECT s.public_id, s.document_number, s.status, s.scheduled_at, s.packed_at,
                       s.shipped_at, s.tracking_number, w.public_id warehouse_public_id,
                       carrier.public_id carrier_partner_public_id, carrier.partner_name carrier_name,
                       s.created_at
                FROM outbound_shipments s
                JOIN warehouses w ON w.id=s.warehouse_id AND w.tenant_id=s.tenant_id
                LEFT JOIN business_partners carrier
                  ON carrier.id=s.shipping_carrier_partner_id AND carrier.tenant_id=s.tenant_id
                WHERE s.tenant_id=? AND s.status IN ('PACKED','SHIPPED')
                ORDER BY s.updated_at DESC,s.id DESC LIMIT 200
                """, a.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(WmsAuthenticatedPrincipal principal, String shipmentPublicId) {
        Access a = access(principal, "WMS_SHIPPING_READ");
        Map<String, Object> shipment = shipment(a.tenantId(), shipmentPublicId);
        Map<String, Object> result = publicShipment(shipment);
        result.put("packages", jdbc.queryForList("""
                SELECT p.public_id,p.package_number,p.package_type,p.status,p.weight,p.weight_uom_id,
                       p.length,p.width,p.height,p.sealed_at
                FROM packing_packages p
                JOIN packing_sessions ps ON ps.id=p.packing_session_id AND ps.tenant_id=p.tenant_id
                WHERE p.tenant_id=? AND p.outbound_shipment_id=? AND ps.status='COMPLETED'
                ORDER BY p.id
                """, a.tenantId(), number(shipment, "id")));
        return result;
    }

    /**
     * Ships only a fully packed shipment. The ISSUE legs are reconstructed from
     * picking confirmation ledger entries (ordinary/lot stock) or the current
     * locations of the exact packed serial identities.
     */
    @Transactional
    public Map<String, Object> ship(WmsAuthenticatedPrincipal principal, String shipmentPublicId,
                                    ShipCommand command) {
        if (command == null || command.requestId() == null) {
            throw new IllegalArgumentException("Shipping request UUID is required");
        }
        lockTenant(principal);
        Access a = access(principal, "WMS_SHIPPING_SHIP");
        Map<String, Object> shipment = shipmentForUpdate(a.tenantId(), shipmentPublicId);
        if (!"PACKED".equals(shipment.get("status"))) {
            throw new IllegalStateException("Only packed shipments can be shipped");
        }

        String trackingNumber = optional(command.trackingNumber(), 120);
        String notes = optional(command.notes(), 1000);
        Long carrierId = resolveCarrier(a.tenantId(), command.carrierPartnerPublicId());
        long shipmentId = number(shipment, "id");
        validatePackedShipment(a.tenantId(), shipmentId);
        List<WmsInventoryPostingService.PostingLeg> legs =
                buildIssueLegs(a.tenantId(), shipment, notes);
        if (legs.isEmpty()) throw new IllegalStateException("Packed shipment has no inventory to ship");
        if (legs.size() > MAX_POSTING_LEGS) {
            throw new IllegalStateException("Shipping exceeds the inventory posting limit of 200 legs");
        }

        postingService.post(principal, new WmsInventoryPostingService.PostingCommand(
                command.requestId(), "ISSUE", legs, notes == null ? "Outbound shipment shipped" : notes));

        int lineCount = count("SELECT COUNT(*) FROM outbound_shipment_lines WHERE tenant_id=? AND outbound_shipment_id=?",
                a.tenantId(), shipmentId);
        int allocationCount = count("""
                SELECT COUNT(*) FROM outbound_shipment_allocations a
                JOIN outbound_shipment_lines l ON l.id=a.outbound_shipment_line_id AND l.tenant_id=a.tenant_id
                WHERE a.tenant_id=? AND l.outbound_shipment_id=?
                """, a.tenantId(), shipmentId);
        int changedLines = jdbc.update("""
                UPDATE outbound_shipment_lines
                SET shipped_qty=picked_qty,status='SHIPPED',updated_at=UTC_TIMESTAMP(6)
                WHERE tenant_id=? AND outbound_shipment_id=? AND picked_qty>0
                  AND shipped_qty=0 AND status IN ('PICKED','PARTIALLY_SHIPPED')
                """, a.tenantId(), shipmentId);
        int changedAllocations = jdbc.update("""
                UPDATE outbound_shipment_allocations a
                JOIN outbound_shipment_lines l ON l.id=a.outbound_shipment_line_id AND l.tenant_id=a.tenant_id
                SET a.shipped_qty=a.picked_qty,a.status='SHIPPED',a.updated_at=UTC_TIMESTAMP(6)
                WHERE a.tenant_id=? AND l.outbound_shipment_id=? AND a.picked_qty>0
                  AND a.shipped_qty=0 AND a.status IN ('PICKED','PARTIALLY_SHIPPED')
                """, a.tenantId(), shipmentId);
        if (lineCount == 0 || changedLines != lineCount || allocationCount == 0
                || changedAllocations != allocationCount) {
            throw new IllegalStateException("Shipment lines and allocations are not fully picked and ready to ship");
        }

        jdbc.update("""
                UPDATE outbound_shipment_line_serials sl
                JOIN outbound_shipment_allocations a
                  ON a.id=sl.outbound_shipment_allocation_id AND a.tenant_id=sl.tenant_id
                JOIN outbound_shipment_lines l
                  ON l.id=a.outbound_shipment_line_id AND l.tenant_id=a.tenant_id
                SET sl.status='SHIPPED',sl.shipped_at=UTC_TIMESTAMP(6)
                WHERE sl.tenant_id=? AND l.outbound_shipment_id=? AND sl.status='PICKED'
                """, a.tenantId(), shipmentId);

        int changedShipment = jdbc.update("""
                UPDATE outbound_shipments
                SET status='SHIPPED',shipped_at=UTC_TIMESTAMP(6),posted_at=UTC_TIMESTAMP(6),
                    shipped_by_membership_id=?,shipping_carrier_partner_id=?,tracking_number=?,
                    version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='PACKED'
                """, a.membershipId(), carrierId, trackingNumber, shipmentId, a.tenantId());
        if (changedShipment != 1) {
            throw new IllegalStateException("Shipment state changed while shipping; refresh and retry");
        }

        audit(a, "OUTBOUND_SHIPMENT_SHIPPED", shipmentPublicId,
                Map.of("requestId", command.requestId().toString(),
                        "postingLegCount", legs.size(),
                        "trackingNumber", trackingNumber == null ? "" : trackingNumber));
        return publicShipment(shipment(a.tenantId(), shipmentPublicId));
    }

    private void validatePackedShipment(long tenantId, long shipmentId) {
        if (count("""
                SELECT COUNT(*) FROM packing_sessions
                WHERE tenant_id=? AND outbound_shipment_id=? AND status='COMPLETED'
                """, tenantId, shipmentId) == 0) {
            throw new IllegalStateException("Shipment has no completed packing session");
        }
        if (count("""
                SELECT COUNT(*) FROM packing_sessions ps
                JOIN packing_packages p ON p.packing_session_id=ps.id AND p.tenant_id=ps.tenant_id
                WHERE ps.tenant_id=? AND ps.outbound_shipment_id=? AND ps.status='COMPLETED'
                  AND p.status<>'SEALED'
                """, tenantId, shipmentId) > 0) {
            throw new IllegalStateException("Every package must be sealed before shipping");
        }
        if (count("""
                SELECT COUNT(*) FROM picking_task_lines ptl
                LEFT JOIN (
                    SELECT picking_task_line_id,SUM(packed_qty) packed_qty
                    FROM packing_package_lines WHERE tenant_id=? GROUP BY picking_task_line_id
                ) packed ON packed.picking_task_line_id=ptl.id
                WHERE ptl.tenant_id=? AND ptl.outbound_shipment_id=?
                  AND (ptl.picked_qty<=0 OR COALESCE(packed.packed_qty,0.0000)<>ptl.picked_qty)
                """, tenantId, tenantId, shipmentId) > 0) {
            throw new IllegalStateException("Packed quantities must exactly reconcile with picked quantities");
        }
        if (count("""
                SELECT COUNT(*) FROM outbound_shipment_lines
                WHERE tenant_id=? AND outbound_shipment_id=?
                  AND (picked_qty<=0 OR picked_qty<>allocated_qty OR shipped_qty<>0
                       OR allocated_qty+cancelled_qty<>requested_qty)
                """, tenantId, shipmentId) > 0) {
            throw new IllegalStateException("Every shipment line must be fully picked and not previously shipped");
        }
    }

    private List<WmsInventoryPostingService.PostingLeg> buildIssueLegs(
            long tenantId, Map<String, Object> shipment, String notes) {
        long shipmentId = number(shipment, "id");
        String warehousePublicId = string(shipment, "warehouse_public_id");
        List<Map<String, Object>> taskLines = jdbc.queryForList("""
                SELECT ptl.id,ptl.picked_qty,ptl.item_id,i.public_id item_public_id,
                       i.tracking_method,lot.public_id lot_public_id
                FROM picking_task_lines ptl
                JOIN items i ON i.id=ptl.item_id AND i.tenant_id=ptl.tenant_id
                LEFT JOIN outbound_shipment_allocations a
                  ON a.id=ptl.outbound_shipment_allocation_id AND a.tenant_id=ptl.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id=a.lot_id AND lot.tenant_id=a.tenant_id
                WHERE ptl.tenant_id=? AND ptl.outbound_shipment_id=?
                ORDER BY ptl.id FOR UPDATE
                """, tenantId, shipmentId);
        Map<String, WmsInventoryPostingService.PostingLeg> aggregated = new LinkedHashMap<>();
        List<WmsInventoryPostingService.PostingLeg> serialLegs = new ArrayList<>();

        for (Map<String, Object> line : taskLines) {
            long taskLineId = number(line, "id");
            BigDecimal picked = decimal(line, "picked_qty");
            BigDecimal packed = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(packed_qty),0.0000)
                    FROM packing_package_lines
                    WHERE tenant_id=? AND picking_task_line_id=?
                    """, BigDecimal.class, tenantId, taskLineId);
            if (packed == null || packed.compareTo(picked) != 0) {
                throw new IllegalStateException("Picking line packed quantity does not reconcile");
            }
            String tracking = string(line, "tracking_method");
            String itemPublicId = string(line, "item_public_id");
            String lotPublicId = nullable(line.get("lot_public_id"));

            if (Set.of("SERIAL", "LOT_AND_SERIAL").contains(tracking)) {
                List<Map<String, Object>> serialRows = jdbc.queryForList("""
                        SELECT s.public_id serial_public_id,s.status serial_status,
                               s.current_warehouse_id,s.current_location_id,
                               loc.public_id location_public_id,loc.location_type,loc.status location_status
                        FROM packing_package_serials ps
                        JOIN inventory_serials s ON s.id=ps.serial_id AND s.tenant_id=ps.tenant_id
                        JOIN warehouse_locations loc
                          ON loc.id=s.current_location_id AND loc.warehouse_id=s.current_warehouse_id
                         AND loc.tenant_id=s.tenant_id
                        WHERE ps.tenant_id=? AND ps.picking_task_line_id=?
                        ORDER BY s.id FOR UPDATE
                        """, tenantId, taskLineId);
                if (BigDecimal.valueOf(serialRows.size()).compareTo(picked) != 0) {
                    throw new IllegalStateException("Packed serial identities do not match picked quantity");
                }
                for (Map<String, Object> serial : serialRows) {
                    if (!"AVAILABLE".equals(serial.get("serial_status"))
                            || !"STAGING".equals(serial.get("location_type"))
                            || !"ACTIVE".equals(serial.get("location_status"))
                            || number(serial, "current_warehouse_id") != number(shipment, "warehouse_id")) {
                        throw new IllegalStateException("Packed serial is not available in an active staging location");
                    }
                    serialLegs.add(new WmsInventoryPostingService.PostingLeg(
                            warehousePublicId, string(serial, "location_public_id"), itemPublicId,
                            lotPublicId, string(serial, "serial_public_id"), null,
                            "AVAILABLE", BigDecimal.ONE.setScale(4)));
                }
            } else {
                BigDecimal remaining = packed;
                List<Map<String, Object>> confirmations = jdbc.queryForList("""
                        SELECT request_id,confirmed_qty
                        FROM picking_confirmations
                        WHERE tenant_id=? AND picking_task_line_id=? AND confirmation_type='PICK'
                        ORDER BY confirmed_at,id
                        """, tenantId, taskLineId);
                for (Map<String, Object> confirmation : confirmations) {
                    if (remaining.signum() == 0) break;
                    List<Map<String, Object>> transfers = jdbc.queryForList("""
                            SELECT il.quantity_delta,loc.public_id location_public_id,
                                   loc.location_type,loc.status location_status,
                                   il.item_id,il.lot_id
                            FROM inventory_ledger il
                            JOIN warehouse_locations loc
                              ON loc.id=il.location_id AND loc.warehouse_id=il.warehouse_id
                             AND loc.tenant_id=il.tenant_id
                            WHERE il.tenant_id=? AND il.request_id=? AND il.movement_type='TRANSFER'
                              AND il.quantity_delta>0
                            ORDER BY il.entry_no
                            """, tenantId, confirmation.get("request_id"));
                    BigDecimal transferTotal = BigDecimal.ZERO.setScale(4);
                    for (Map<String, Object> transfer : transfers) {
                        transferTotal = transferTotal.add(decimal(transfer, "quantity_delta"));
                    }
                    if (transferTotal.compareTo(decimal(confirmation, "confirmed_qty")) != 0) {
                        throw new IllegalStateException("Picking confirmation is missing its matching staging ledger movement");
                    }
                    for (Map<String, Object> transfer : transfers) {
                        if (remaining.signum() == 0) break;
                        if (number(transfer, "item_id") != number(line, "item_id")
                                || !Objects.equals(nullableNumber(transfer.get("lot_id")),
                                                   nullableNumber(findLotId(tenantId, line)))) {
                            throw new IllegalStateException("Picking ledger movement does not match its shipment line");
                        }
                        if (!"STAGING".equals(transfer.get("location_type"))
                                || !"ACTIVE".equals(transfer.get("location_status"))) {
                            throw new IllegalStateException("Picked stock is not in an active staging location");
                        }
                        BigDecimal quantity = decimal(transfer, "quantity_delta").min(remaining);
                        String locationPublicId = string(transfer, "location_public_id");
                        String key = locationPublicId + "|" + itemPublicId + "|" + Objects.toString(lotPublicId, "");
                        WmsInventoryPostingService.PostingLeg prior = aggregated.get(key);
                        BigDecimal combined = prior == null ? quantity : prior.quantity().add(quantity);
                        aggregated.put(key, new WmsInventoryPostingService.PostingLeg(
                                warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                                null, null, "AVAILABLE", combined));
                        remaining = remaining.subtract(quantity);
                    }
                }
                if (remaining.signum() != 0) {
                    throw new IllegalStateException("Packed quantity cannot be traced to picked staging inventory");
                }
            }
        }
        List<WmsInventoryPostingService.PostingLeg> result = new ArrayList<>(aggregated.values());
        result.addAll(serialLegs);
        return List.copyOf(result);
    }

    private Long findLotId(long tenantId, Map<String, Object> line) {
        List<Long> ids = jdbc.queryForList("""
                SELECT a.lot_id FROM picking_task_lines ptl
                JOIN outbound_shipment_allocations a
                  ON a.id=ptl.outbound_shipment_allocation_id AND a.tenant_id=ptl.tenant_id
                WHERE ptl.tenant_id=? AND ptl.id=?
                """, Long.class, tenantId, number(line, "id"));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Long resolveCarrier(long tenantId, String publicId) {
        if (publicId == null || publicId.isBlank()) return null;
        return requiredLong("""
                SELECT id FROM business_partners
                WHERE tenant_id=? AND public_id=? AND status='ACTIVE'
                """, tenantId, required(publicId, "carrier partner public ID", 36));
    }

    private Map<String, Object> shipment(long tenantId, String publicId) {
        return requiredRow("""
                SELECT s.*,w.public_id warehouse_public_id,
                       carrier.public_id carrier_partner_public_id,carrier.partner_name carrier_name,
                       member.public_id shipped_by_membership_public_id
                FROM outbound_shipments s
                JOIN warehouses w ON w.id=s.warehouse_id AND w.tenant_id=s.tenant_id
                LEFT JOIN business_partners carrier
                  ON carrier.id=s.shipping_carrier_partner_id AND carrier.tenant_id=s.tenant_id
                LEFT JOIN tenant_memberships member
                  ON member.id=s.shipped_by_membership_id AND member.tenant_id=s.tenant_id
                WHERE s.tenant_id=? AND s.public_id=?
                """, tenantId, required(publicId, "shipment public ID", 36));
    }

    private Map<String, Object> shipmentForUpdate(long tenantId, String publicId) {
        return requiredRow("""
                SELECT s.*,w.public_id warehouse_public_id
                FROM outbound_shipments s
                JOIN warehouses w ON w.id=s.warehouse_id AND w.tenant_id=s.tenant_id
                WHERE s.tenant_id=? AND s.public_id=? FOR UPDATE
                """, tenantId, required(publicId, "shipment public ID", 36));
    }

    private Map<String, Object> publicShipment(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("id");
        result.remove("tenant_id");
        result.remove("warehouse_id");
        result.remove("customer_partner_id");
        result.remove("customer_address_id");
        result.remove("shipping_carrier_partner_id");
        result.remove("created_by_membership_id");
        result.remove("picked_by_membership_id");
        result.remove("shipped_by_membership_id");
        result.remove("version");
        return result;
    }

    private void lockTenant(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        if (jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE",
                Long.class, principal.tenantId()).isEmpty()) {
            throw new AccessDeniedException("Active tenant is unavailable");
        }
    }

    private Access access(WmsAuthenticatedPrincipal principal, String permission) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess resolved = accessService.resolve(
                principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(resolved.userPublicId(), principal.userPublicId())
                || !Objects.equals(resolved.tenantId(), principal.tenantId())
                || !Objects.equals(resolved.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(resolved.membershipId(), principal.membershipId())
                || !Objects.equals(resolved.membershipPublicId(), principal.membershipPublicId())) {
            throw new AccessDeniedException("Tenant context is unavailable");
        }
        if (!resolved.hasPermission(permission)) {
            throw new AccessDeniedException("Shipping permission is required");
        }
        return new Access(resolved.tenantId(), resolved.membershipId(), resolved.userPublicId());
    }

    private void audit(Access access, String type, String subject, Map<String, Object> payload) {
        final String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Shipping audit payload serialization failed", failure);
        }
        int rows = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id,tenant_id,actor_membership_id,actor_type,operation_ref,
                   event_type,subject_public_id,change_payload)
                SELECT ?,t.id,m.id,'TENANT_MEMBER',?,?,?,?
                FROM tenants t
                JOIN tenant_memberships m ON m.id=? AND m.tenant_id=t.id AND m.status='ACTIVE'
                JOIN users u ON u.id=m.user_id AND u.public_id=? AND u.status='ACTIVE'
                WHERE t.id=? AND t.status='ACTIVE'
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                type, subject, json, access.membershipId(), access.userPublicId(), access.tenantId());
        if (rows != 1) throw new AccessDeniedException("Audit actor is no longer active");
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new IllegalArgumentException("Referenced shipping record was not found");
        return rows.getFirst();
    }

    private long requiredLong(String sql, Object... args) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, args);
        if (rows.isEmpty()) throw new IllegalArgumentException("Referenced record was not found");
        return rows.getFirst();
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) return number.longValue();
        throw new IllegalStateException("Missing numeric field " + key);
    }

    private static Long nullableNumber(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof BigDecimal decimal) return decimal;
        throw new IllegalStateException("Missing decimal field " + key);
    }

    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof String string) return string;
        throw new IllegalStateException("Missing text field " + key);
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw new IllegalArgumentException("Text exceeds maximum length");
        return value.trim();
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(label + " is required and must not exceed " + max + " characters");
        }
        return value.trim();
    }

    private record Access(long tenantId, long membershipId, String userPublicId) {}
    public record ShipCommand(UUID requestId, String carrierPartnerPublicId,
                              String trackingNumber, String notes) {}
}
