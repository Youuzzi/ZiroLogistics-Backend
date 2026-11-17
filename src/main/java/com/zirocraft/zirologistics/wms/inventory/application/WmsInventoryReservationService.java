package com.zirocraft.zirologistics.wms.inventory.application;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The controlled application boundary for inventory reservations.
 * Only this service may change inventory_balances.reserved_qty.
 */
@Service
@Profile("wms")
public class WmsInventoryReservationService {
    private final WmsMembershipAccessService accessService;
    private final JdbcTemplate jdbc;

    public WmsInventoryReservationService(WmsMembershipAccessService accessService, JdbcTemplate jdbc) {
        this.accessService = Objects.requireNonNull(accessService);
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Transactional
    public void reserve(WmsAuthenticatedPrincipal principal, ReservationCommand command) {
        if (command == null) throw new IllegalArgumentException("Reservation command is required");
        Access access = lockTenantAndResolveAccess(principal);
        BigDecimal quantity = quantity(command.quantity());
        long warehouseId = id("SELECT id FROM warehouses WHERE tenant_id = ? AND public_id = ? AND status = 'ACTIVE'",
                access.tenantId(), required(command.warehousePublicId()), "Active warehouse not found");
        lockIds("warehouses", List.of(warehouseId), access.tenantId(), "Warehouse");

        long locationId = id("""
                SELECT id FROM warehouse_locations
                WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ? AND status = 'ACTIVE'
                """, access.tenantId(), warehouseId, required(command.locationPublicId()), "Active location not found");
        lockIds("warehouse_locations", List.of(locationId), access.tenantId(), "Location");

        Item item = jdbc.query("""
                SELECT id, tracking_method FROM items
                WHERE tenant_id = ? AND public_id = ? AND status = 'ACTIVE' AND is_stock_item = 1
                """, rs -> rs.next() ? new Item(rs.getLong("id"), rs.getString("tracking_method")) : null,
                access.tenantId(), required(command.itemPublicId()));
        if (item == null) throw new IllegalArgumentException("Active stock item not found");
        lockIds("items", List.of(item.id()), access.tenantId(), "Item");

        Long lotId = null;
        if (command.lotPublicId() != null && !command.lotPublicId().isBlank()) {
            lotId = id("""
                    SELECT id FROM inventory_lots
                    WHERE tenant_id = ? AND item_id = ? AND public_id = ? AND status = 'ACTIVE'
                    """, access.tenantId(), item.id(), command.lotPublicId().trim(), "Active item lot not found");
            lockIds("inventory_lots", List.of(lotId), access.tenantId(), "Lot");
        }
        boolean lotTracked = "LOT".equals(item.trackingMethod()) || "LOT_AND_SERIAL".equals(item.trackingMethod());
        boolean serialTracked = "SERIAL".equals(item.trackingMethod()) || "LOT_AND_SERIAL".equals(item.trackingMethod());
        if (lotTracked != (lotId != null)) throw new IllegalArgumentException("Lot allocation does not match item tracking method");

        List<String> serialPublicIds = command.serialPublicIds() == null ? List.of() : command.serialPublicIds();
        if (serialTracked) {
            if (quantity.stripTrailingZeros().scale() > 0
                    || quantity.compareTo(BigDecimal.valueOf(serialPublicIds.size())) != 0
                    || serialPublicIds.isEmpty()) {
                throw new IllegalArgumentException("Serial allocation count must exactly match whole-unit quantity");
            }
        } else if (!serialPublicIds.isEmpty()) {
            throw new IllegalArgumentException("Serial IDs are only valid for serial-tracked items");
        }

        List<Long> serialIds = serialPublicIds.stream().distinct().map(serialPublicId -> id("""
                SELECT id FROM inventory_serials
                WHERE tenant_id = ? AND item_id = ? AND public_id = ? AND lot_id <=> ?
                  AND status = 'AVAILABLE' AND current_warehouse_id = ? AND current_location_id = ?
                """, access.tenantId(), item.id(), required(serialPublicId), lotId, warehouseId, locationId,
                "Available serial at source location not found")).sorted().toList();
        if (serialIds.size() != serialPublicIds.size()) throw new IllegalArgumentException("Duplicate serial IDs are not allowed");
        lockIds("inventory_serials", serialIds, access.tenantId(), "Serial");

        if (!serialIds.isEmpty()) {
            Integer alreadyAllocated = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM outbound_shipment_line_serials s
                    JOIN outbound_shipment_allocations a
                      ON a.id = s.outbound_shipment_allocation_id AND a.tenant_id = s.tenant_id
                    WHERE s.tenant_id = ? AND s.serial_id IN (%s) AND s.status <> 'CANCELLED'
                      AND a.status <> 'CANCELLED'
                    """.formatted(String.join(",", java.util.Collections.nCopies(serialIds.size(), "?"))),
                    Integer.class, prepend(access.tenantId(), serialIds.toArray()));
            if (alreadyAllocated != null && alreadyAllocated > 0) {
                throw new IllegalStateException("One or more serials are already allocated to an outbound shipment");
            }
        }

        List<java.util.Map<String, Object>> balances = jdbc.queryForList("""
                SELECT id, available_qty FROM inventory_balances
                WHERE tenant_id = ? AND warehouse_id = ? AND location_id = ? AND item_id = ?
                  AND lot_id <=> ? AND stock_status = 'AVAILABLE'
                FOR UPDATE
                """, access.tenantId(), warehouseId, locationId, item.id(), lotId);
        if (balances.isEmpty()) throw new IllegalStateException("No available inventory balance exists for this allocation");
        long balanceId = ((Number) balances.getFirst().get("id")).longValue();
        BigDecimal available = (BigDecimal) balances.getFirst().get("available_qty");
        if (available.compareTo(quantity) < 0) throw new IllegalStateException("Insufficient available inventory");
        int updated = jdbc.update("""
                UPDATE inventory_balances
                SET reserved_qty = reserved_qty + ?, version = version + 1
                WHERE id = ? AND tenant_id = ? AND stock_status = 'AVAILABLE'
                  AND available_qty >= ?
                """, quantity, balanceId, access.tenantId(), quantity);
        if (updated != 1) throw new IllegalStateException("Inventory availability changed; retry allocation");
    }

    private Access lockTenantAndResolveAccess(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        List<Long> tenants = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                Long.class, principal.tenantId());
        if (tenants.isEmpty()) throw new AccessDeniedException("Active tenant is unavailable");
        WmsMembershipAccess resolved = accessService.resolve(principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(resolved.userPublicId(), principal.userPublicId())
                || !Objects.equals(resolved.tenantId(), principal.tenantId())
                || !Objects.equals(resolved.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(resolved.membershipId(), principal.membershipId())
                || !Objects.equals(resolved.membershipPublicId(), principal.membershipPublicId())) {
            throw new AccessDeniedException("Tenant context is unavailable");
        }
        if (!resolved.hasPermission("WMS_INVENTORY_RESERVE")) {
            throw new AccessDeniedException("Inventory reservation permission is required");
        }
        return new Access(resolved.tenantId());
    }

    private void lockIds(String table, List<Long> ids, long tenantId, String label) {
        ids.stream().distinct().sorted().forEach(id -> {
            List<Long> rows = jdbc.queryForList(
                    "SELECT id FROM " + table + " WHERE id = ? AND tenant_id = ? FOR UPDATE",
                    Long.class, id, tenantId);
            if (rows.isEmpty()) throw new IllegalArgumentException(label + " is no longer available");
        });
    }

    private long id(String sql, Object a, Object b, String message) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, a, b);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.getFirst();
    }

    private long id(String sql, Object a, Object b, Object c, String message) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, a, b, c);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.getFirst();
    }

    private long id(String sql, Object a, Object b, Object c, Object d, Object e, Object f, String message) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, a, b, c, d, e, f);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.getFirst();
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Reservation quantity must be positive");
        try { return value.setScale(4, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Reservation quantity supports at most four decimals", ex); }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Public ID is required");
        return value.trim();
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] result = new Object[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }

    public record ReservationCommand(String warehousePublicId, String locationPublicId,
                                    String itemPublicId, String lotPublicId,
                                    BigDecimal quantity, List<String> serialPublicIds) {}
    private record Item(long id, String trackingMethod) {}
    private record Access(long tenantId) {}
}
