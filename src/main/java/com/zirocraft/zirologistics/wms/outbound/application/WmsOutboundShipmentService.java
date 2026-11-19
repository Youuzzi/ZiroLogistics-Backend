package com.zirocraft.zirologistics.wms.outbound.application;

import com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Tenant-scoped outbound shipment drafts, stock allocations and release. */
@Service
@Profile("wms")
public class WmsOutboundShipmentService {
    private static final Set<String> SHIPMENT_TYPES = Set.of("SALES", "OTHER");

    private final WmsMembershipAccessService accessService;
    private final WmsInventoryReservationService reservationService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsOutboundShipmentService(WmsMembershipAccessService accessService,
                                      WmsInventoryReservationService reservationService,
                                      JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.reservationService = Objects.requireNonNull(reservationService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(WmsAuthenticatedPrincipal principal) {
        Access access = access(principal, "WMS_OUTBOUND_SHIPMENT_READ");
        return jdbc.queryForList("""
                SELECT s.public_id, s.document_number, s.external_reference, s.shipment_type,
                       s.status, s.scheduled_at, s.tracking_number, s.notes, s.version,
                       s.created_at, s.updated_at, w.public_id AS warehouse_public_id,
                       w.warehouse_code, w.warehouse_name, bp.public_id AS customer_public_id,
                       bp.partner_code AS customer_code, bp.partner_name AS customer_name
                FROM outbound_shipments s
                JOIN warehouses w ON w.id = s.warehouse_id AND w.tenant_id = s.tenant_id
                JOIN business_partners bp ON bp.id = s.customer_partner_id AND bp.tenant_id = s.tenant_id
                WHERE s.tenant_id = ?
                ORDER BY s.created_at DESC, s.id DESC
                LIMIT 200
                """, access.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(WmsAuthenticatedPrincipal principal, String shipmentPublicId) {
        Access access = access(principal, "WMS_OUTBOUND_SHIPMENT_READ");
        Map<String, Object> raw = shipment(access.tenantId(), shipmentPublicId);
        Map<String, Object> result = publicShipment(raw);
        result.put("lines", jdbc.queryForList("""
                SELECT l.public_id, l.line_number, i.public_id AS item_public_id, i.sku, i.item_name,
                       u.uom_code, i.tracking_method, l.requested_qty, l.allocated_qty,
                       l.picked_qty, l.shipped_qty, l.cancelled_qty, l.status, l.notes
                FROM outbound_shipment_lines l
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                JOIN units_of_measure u ON u.id = l.uom_id
                WHERE l.tenant_id = ? AND l.outbound_shipment_id = ?
                ORDER BY l.line_number
                """, access.tenantId(), number(raw, "id")));
        result.put("allocations", jdbc.queryForList("""
                SELECT l.public_id AS shipment_line_public_id,
                       w.public_id AS warehouse_public_id, w.warehouse_code,
                       loc.public_id AS location_public_id, loc.location_code,
                       i.public_id AS item_public_id, i.sku, lot.public_id AS lot_public_id,
                       lot.lot_number, a.allocated_qty, a.picked_qty, a.shipped_qty, a.status,
                       (SELECT COUNT(*) FROM outbound_shipment_line_serials ss
                        WHERE ss.tenant_id = a.tenant_id
                          AND ss.outbound_shipment_allocation_id = a.id
                          AND ss.status <> 'CANCELLED') AS allocated_serial_count
                FROM outbound_shipment_allocations a
                JOIN outbound_shipment_lines l
                  ON l.id = a.outbound_shipment_line_id AND l.tenant_id = a.tenant_id
                JOIN warehouses w ON w.id = a.warehouse_id AND w.tenant_id = a.tenant_id
                JOIN warehouse_locations loc
                  ON loc.id = a.location_id AND loc.warehouse_id = a.warehouse_id AND loc.tenant_id = a.tenant_id
                JOIN items i ON i.id = a.item_id AND i.tenant_id = a.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id = a.lot_id AND lot.tenant_id = a.tenant_id
                WHERE a.tenant_id = ? AND l.outbound_shipment_id = ?
                ORDER BY l.line_number, a.id
                """, access.tenantId(), number(raw, "id")));
        return result;
    }

    @Transactional
    public Map<String, Object> create(WmsAuthenticatedPrincipal principal, CreateShipmentCommand command) {
        Objects.requireNonNull(command, "Outbound shipment request is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_OUTBOUND_SHIPMENT_CREATE");
        String documentNumber = required(command.documentNumber(), "document number", 60).toUpperCase(Locale.ROOT);
        String type = enumValue(command.shipmentType() == null ? "SALES" : command.shipmentType(),
                SHIPMENT_TYPES, "shipment type");
        Map<String, Object> warehouse = requiredRow("""
                SELECT id, status FROM warehouses WHERE tenant_id = ? AND public_id = ?
                """, access.tenantId(), required(command.warehousePublicId(), "warehouse public ID"), "Warehouse not found");
        if (!"ACTIVE".equals(warehouse.get("status"))) throw new IllegalArgumentException("Warehouse is not active");
        Map<String, Object> customer = requiredRow("""
                SELECT bp.id, bp.status
                FROM business_partners bp
                JOIN business_partner_roles r
                  ON r.business_partner_id = bp.id AND r.tenant_id = bp.tenant_id
                WHERE bp.tenant_id = ? AND bp.public_id = ? AND r.role_type = 'CUSTOMER'
                """, access.tenantId(), required(command.customerPublicId(), "customer public ID"), "Customer not found");
        if (!"ACTIVE".equals(customer.get("status"))) throw new IllegalArgumentException("Customer is not active");
        Long addressId = null;
        if (command.customerAddressPublicId() != null && !command.customerAddressPublicId().isBlank()) {
            addressId = requiredLong("""
                    SELECT id FROM business_partner_addresses
                    WHERE tenant_id = ? AND business_partner_id = ? AND public_id = ?
                    """, access.tenantId(), number(customer, "id"), command.customerAddressPublicId().trim(),
                    "Customer address not found");
        }
        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO outbound_shipments
                      (public_id, tenant_id, warehouse_id, customer_partner_id, customer_address_id,
                       document_number, external_reference, shipment_type, status, scheduled_at,
                       created_by_membership_id, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                    """, publicId, access.tenantId(), number(warehouse, "id"), number(customer, "id"),
                    addressId, documentNumber, optional(command.externalReference(), 100), type,
                    command.scheduledAt(), access.membershipId(), optional(command.notes(), 1000));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Outbound shipment document number already exists", duplicate);
        }
        Map<String, Object> created = shipment(access.tenantId(), publicId);
        audit(access, "OUTBOUND_SHIPMENT_CREATED", publicId, Map.of(), publicShipment(created));
        return publicShipment(created);
    }

    @Transactional
    public Map<String, Object> addLine(WmsAuthenticatedPrincipal principal, String shipmentPublicId,
                                       ShipmentLineCommand command) {
        Objects.requireNonNull(command, "Shipment line request is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_OUTBOUND_SHIPMENT_UPDATE");
        Map<String, Object> header = shipment(access.tenantId(), shipmentPublicId);
        if (!"DRAFT".equals(header.get("status"))) throw new IllegalStateException("Only draft shipments can be changed");
        Map<String, Object> item = requiredRow("""
                SELECT id, base_uom_id, tracking_method, is_stock_item, is_sellable, status
                FROM items WHERE tenant_id = ? AND public_id = ?
                """, access.tenantId(), required(command.itemPublicId(), "item public ID"), "Item not found");
        if (!"ACTIVE".equals(item.get("status")) || !bool(item.get("is_stock_item"))
                || !bool(item.get("is_sellable"))) {
            throw new IllegalArgumentException("Shipment lines require an active sellable stock item");
        }
        BigDecimal requested = quantity(command.requestedQty());
        int lineNumber = jdbc.queryForObject("""
                SELECT COALESCE(MAX(line_number), 0) + 1
                FROM outbound_shipment_lines WHERE tenant_id = ? AND outbound_shipment_id = ?
                """, Integer.class, access.tenantId(), number(header, "id"));
        String linePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO outbound_shipment_lines
                  (public_id, tenant_id, outbound_shipment_id, line_number, item_id, uom_id,
                   requested_qty, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, linePublicId, access.tenantId(), number(header, "id"), lineNumber,
                number(item, "id"), number(item, "base_uom_id"), requested, optional(command.notes(), 1000));
        Map<String, Object> created = requiredRow("""
                SELECT l.public_id, l.line_number, i.public_id AS item_public_id, i.sku,
                       i.item_name, u.uom_code, l.requested_qty, l.status, l.notes
                FROM outbound_shipment_lines l
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                JOIN units_of_measure u ON u.id = l.uom_id
                WHERE l.tenant_id = ? AND l.public_id = ?
                """, access.tenantId(), linePublicId, "Created shipment line not found");
        audit(access, "OUTBOUND_SHIPMENT_LINE_ADDED", linePublicId,
                Map.of("shipmentPublicId", shipmentPublicId), created);
        return created;
    }

    @Transactional
    public Map<String, Object> allocate(WmsAuthenticatedPrincipal principal, String shipmentPublicId,
                                        String linePublicId, AllocationCommand command) {
        Objects.requireNonNull(command, "Allocation request is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_OUTBOUND_SHIPMENT_ALLOCATE");
        Map<String, Object> header = shipment(access.tenantId(), shipmentPublicId);
        if (!"DRAFT".equals(header.get("status"))) throw new IllegalStateException("Only draft shipments can be allocated");
        Map<String, Object> line = requiredRow("""
                SELECT l.id, l.item_id, l.requested_qty, l.allocated_qty,
                       i.public_id AS item_public_id, i.tracking_method
                FROM outbound_shipment_lines l
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.outbound_shipment_id = ? AND l.public_id = ?
                """, access.tenantId(), number(header, "id"), required(linePublicId, "line public ID"),
                "Shipment line not found");
        if (command.allocations() == null || command.allocations().isEmpty() || command.allocations().size() > 100) {
            throw new IllegalArgumentException("Provide between 1 and 100 stock allocations");
        }
        BigDecimal requested = (BigDecimal) line.get("requested_qty");
        BigDecimal alreadyAllocated = (BigDecimal) line.get("allocated_qty");
        BigDecimal requestedThisCommand = command.allocations().stream()
                .map(a -> quantity(a.quantity())).reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
        if (alreadyAllocated.add(requestedThisCommand).compareTo(requested) > 0) {
            throw new IllegalArgumentException("Allocation exceeds remaining shipment quantity");
        }
        BigDecimal total = BigDecimal.ZERO.setScale(4);
        int createdAllocationCount = 0;
        for (AllocationLine allocation : command.allocations()) {
            BigDecimal qty = quantity(allocation.quantity());
            Map<String, Object> location = requiredRow("""
                    SELECT id, status FROM warehouse_locations
                    WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ?
                    """, access.tenantId(), number(header, "warehouse_id"),
                    required(allocation.locationPublicId(), "location public ID"), "Source location not found");
            if (!"ACTIVE".equals(location.get("status"))) throw new IllegalArgumentException("Source location is not active");
            String lotPublicId = allocation.lotPublicId() == null || allocation.lotPublicId().isBlank()
                    ? null : allocation.lotPublicId().trim();
            List<String> serialIds = allocation.serialPublicIds() == null ? List.of() : allocation.serialPublicIds();
            reservationService.reserve(principal, new WmsInventoryReservationService.ReservationCommand(
                    (String) header.get("warehouse_public_id"), allocation.locationPublicId(),
                    (String) line.get("item_public_id"), lotPublicId, qty, serialIds));
            Long lotId = null;
            if (lotPublicId != null) {
                lotId = requiredLong("""
                        SELECT id FROM inventory_lots WHERE tenant_id = ? AND item_id = ? AND public_id = ?
                        """, access.tenantId(), number(line, "item_id"), lotPublicId, "Allocated lot not found");
            }
            List<Map<String, Object>> existingAllocations = jdbc.queryForList("""
                    SELECT id, allocated_qty FROM outbound_shipment_allocations
                    WHERE tenant_id = ? AND outbound_shipment_line_id = ? AND warehouse_id = ?
                      AND location_id = ? AND lot_id <=> ?
                    FOR UPDATE
                    """, access.tenantId(), number(line, "id"), number(header, "warehouse_id"),
                    number(location, "id"), lotId);
            Long allocationId;
            if (existingAllocations.isEmpty()) {
                jdbc.update("""
                        INSERT INTO outbound_shipment_allocations
                          (tenant_id, outbound_shipment_line_id, item_id, warehouse_id, location_id,
                           lot_id, allocated_qty, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'ALLOCATED')
                        """, access.tenantId(), number(line, "id"), number(line, "item_id"),
                        number(header, "warehouse_id"), number(location, "id"), lotId, qty);
                allocationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                createdAllocationCount++;
            } else {
                Map<String, Object> existing = existingAllocations.getFirst();
                allocationId = number(existing, "id");
                BigDecimal combined = ((BigDecimal) existing.get("allocated_qty")).add(qty).setScale(4, RoundingMode.UNNECESSARY);
                jdbc.update("""
                        UPDATE outbound_shipment_allocations
                        SET allocated_qty = ?, status = 'ALLOCATED'
                        WHERE id = ? AND tenant_id = ?
                        """, combined, allocationId, access.tenantId());
            }
            if (!serialIds.isEmpty()) {
                for (String serialPublicId : serialIds) {
                    long serialId = requiredLong("""
                            SELECT id FROM inventory_serials
                            WHERE tenant_id = ? AND item_id = ? AND public_id = ?
                            """, access.tenantId(), number(line, "item_id"), serialPublicId,
                            "Allocated serial not found");
                    jdbc.update("""
                            INSERT INTO outbound_shipment_line_serials
                              (tenant_id, outbound_shipment_allocation_id, item_id, serial_id, status)
                            VALUES (?, ?, ?, ?, 'ALLOCATED')
                            """, access.tenantId(), allocationId, number(line, "item_id"), serialId);
                }
            }
            total = total.add(qty);

        }
        BigDecimal newAllocated = ((BigDecimal) line.get("allocated_qty")).add(total).setScale(4, RoundingMode.UNNECESSARY);
        if (newAllocated.compareTo(requested) > 0) throw new IllegalArgumentException("Allocation exceeds remaining shipment quantity");
        jdbc.update("""
                UPDATE outbound_shipment_lines SET allocated_qty = ?, status = 'ALLOCATED'
                WHERE id = ? AND tenant_id = ?
                """, newAllocated, number(line, "id"), access.tenantId());
        Map<String, Object> after = Map.of("shipmentPublicId", shipmentPublicId,
                "linePublicId", linePublicId, "allocatedQty", newAllocated,
                "allocationBucketCountCreated", createdAllocationCount);
        audit(access, "OUTBOUND_SHIPMENT_ALLOCATED", shipmentPublicId,
                Map.of("allocatedQty", line.get("allocated_qty")), after);
        return after;
    }

    @Transactional
    public Map<String, Object> release(WmsAuthenticatedPrincipal principal, String shipmentPublicId) {
        lockTenant(principal);
        Access access = access(principal, "WMS_OUTBOUND_SHIPMENT_RELEASE");
        Map<String, Object> header = shipment(access.tenantId(), shipmentPublicId);
        if (!"DRAFT".equals(header.get("status"))) throw new IllegalStateException("Only draft shipments can be released");
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT public_id, requested_qty, allocated_qty
                FROM outbound_shipment_lines
                WHERE tenant_id = ? AND outbound_shipment_id = ?
                ORDER BY line_number FOR UPDATE
                """, access.tenantId(), number(header, "id"));
        if (lines.isEmpty()) throw new IllegalStateException("A shipment requires at least one line before release");
        for (Map<String, Object> line : lines) {
            if (((BigDecimal) line.get("allocated_qty")).compareTo((BigDecimal) line.get("requested_qty")) != 0) {
                throw new IllegalStateException("Every shipment line must be fully allocated before release");
            }
        }
        int updated = jdbc.update("""
                UPDATE outbound_shipments SET status = 'RELEASED', version = version + 1
                WHERE id = ? AND tenant_id = ? AND status = 'DRAFT'
                """, number(header, "id"), access.tenantId());
        if (updated != 1) throw new IllegalStateException("Shipment state changed; retry release");
        Map<String, Object> after = Map.of("publicId", shipmentPublicId, "status", "RELEASED");
        audit(access, "OUTBOUND_SHIPMENT_RELEASED", shipmentPublicId,
                Map.of("status", "DRAFT"), after);
        return after;
    }

    private Map<String, Object> shipment(long tenantId, String publicId) {
        return requiredRow("""
                SELECT s.id, s.public_id, s.document_number, s.external_reference, s.shipment_type,
                       s.status, s.scheduled_at, s.tracking_number, s.notes, s.version,
                       s.warehouse_id, w.public_id AS warehouse_public_id, w.warehouse_code, w.warehouse_name,
                       bp.public_id AS customer_public_id, bp.partner_code AS customer_code,
                       bp.partner_name AS customer_name, a.public_id AS customer_address_public_id
                FROM outbound_shipments s
                JOIN warehouses w ON w.id = s.warehouse_id AND w.tenant_id = s.tenant_id
                JOIN business_partners bp ON bp.id = s.customer_partner_id AND bp.tenant_id = s.tenant_id
                LEFT JOIN business_partner_addresses a
                  ON a.id = s.customer_address_id AND a.tenant_id = s.tenant_id
                WHERE s.tenant_id = ? AND s.public_id = ?
                """, tenantId, required(publicId, "shipment public ID"), "Outbound shipment not found");
    }

    private Map<String, Object> requiredRow(String sql, Object a, Object b, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, a, b);
        if (rows.isEmpty()) throw new WmsResourceNotFoundException(message);
        return rows.getFirst();
    }

    private Map<String, Object> requiredRow(String sql, Object a, Object b, Object c, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, a, b, c);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.getFirst();
    }

    private long requiredLong(String sql, Object a, Object b, Object c, String message) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, a, b, c);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.getFirst();
    }

    private Access access(WmsAuthenticatedPrincipal principal, String permission) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess resolved = accessService.resolve(principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(resolved.userPublicId(), principal.userPublicId())
                || !Objects.equals(resolved.tenantId(), principal.tenantId())
                || !Objects.equals(resolved.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(resolved.membershipId(), principal.membershipId())
                || !Objects.equals(resolved.membershipPublicId(), principal.membershipPublicId())) {
            throw new AccessDeniedException("Tenant context is unavailable");
        }
        if (!resolved.hasPermission(permission)) throw new AccessDeniedException("Outbound shipment permission is required");
        return new Access(resolved.tenantId(), resolved.membershipId(), resolved.userPublicId());
    }

    private void lockTenant(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                Long.class, principal.tenantId());
        if (ids.isEmpty()) throw new AccessDeniedException("Active tenant is unavailable");
    }

    private void audit(Access access, String eventType, String subjectPublicId,
                       Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", before);
        payload.put("after", after);
        final String json;
        try { json = mapper.writeValueAsString(payload); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Outbound audit payload could not be serialized", failure); }
        int written = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, operation_ref,
                   event_type, subject_public_id, change_payload)
                SELECT ?, t.id, m.id, 'TENANT_MEMBER', ?, ?, ?, ?
                FROM tenants t
                JOIN tenant_memberships m ON m.id = ? AND m.tenant_id = t.id AND m.status = 'ACTIVE'
                JOIN users u ON u.id = m.user_id AND u.public_id = ? AND u.status = 'ACTIVE'
                WHERE t.id = ? AND t.status = 'ACTIVE'
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), eventType,
                subjectPublicId, json, access.membershipId(), access.userPublicId(), access.tenantId());
        if (written != 1) throw new AccessDeniedException("Audit actor is no longer active");
    }

    private Map<String, Object> publicShipment(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.remove("id");
        result.remove("warehouse_id");
        return result;
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Quantity must be positive");
        try { return value.setScale(4, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Quantity supports at most four decimals", ex); }
    }

    private static String required(String value, String label) {
        return required(value, label, 1024);
    }

    private static String required(String value) {
        return required(value, "public ID", 1024);
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(label + " is required and must not exceed " + max + " characters");
        }
        return value.trim();
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw new IllegalArgumentException("Text exceeds " + max + " characters");
        return value.trim();
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value, label, 30).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException(label + " is not supported in this delivery");
        return normalized;
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number n) return n.longValue();
        throw new IllegalStateException("Expected numeric field: " + key);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }

    private record Access(long tenantId, long membershipId, String userPublicId) {}
    public record CreateShipmentCommand(String warehousePublicId, String customerPublicId,
                                        String customerAddressPublicId, String documentNumber,
                                        String externalReference, String shipmentType,
                                        java.time.LocalDateTime scheduledAt, String notes) {}
    public record ShipmentLineCommand(String itemPublicId, BigDecimal requestedQty, String notes) {}
    public record AllocationCommand(List<AllocationLine> allocations) {}
    public record AllocationLine(String locationPublicId, String lotPublicId,
                                 BigDecimal quantity, List<String> serialPublicIds) {}
}
