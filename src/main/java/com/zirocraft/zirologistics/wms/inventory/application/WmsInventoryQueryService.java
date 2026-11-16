package com.zirocraft.zirologistics.wms.inventory.application;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Tenant-scoped, bounded read access to inventory projections and immutable ledger history. */
@Service
@Profile("wms")
public class WmsInventoryQueryService {
    private final WmsMembershipAccessService accessService;
    private final JdbcTemplate jdbc;

    public WmsInventoryQueryService(WmsMembershipAccessService accessService, JdbcTemplate jdbc) {
        this.accessService = Objects.requireNonNull(accessService);
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> balances(WmsAuthenticatedPrincipal principal, String itemPublicId,
                                        String warehousePublicId, String locationPublicId,
                                        String stockStatus, int page, int size) {
        Access access = access(principal, "WMS_INVENTORY_READ");
        Page request = page(page, size);
        StringBuilder where = new StringBuilder(" WHERE b.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(access.tenantId());
        addFilter(where, args, "i.public_id", itemPublicId);
        addFilter(where, args, "w.public_id", warehousePublicId);
        addFilter(where, args, "l.public_id", locationPublicId);
        addFilter(where, args, "b.stock_status", normalizeFilter(stockStatus,
                List.of("AVAILABLE", "QUARANTINED", "DAMAGED", "EXPIRED", "BLOCKED")));
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_balances b
                JOIN items i ON i.id = b.item_id AND i.tenant_id = b.tenant_id
                JOIN warehouses w ON w.id = b.warehouse_id AND w.tenant_id = b.tenant_id
                JOIN warehouse_locations l ON l.id = b.location_id AND l.warehouse_id = b.warehouse_id
                    AND l.tenant_id = b.tenant_id
                """ + where, Integer.class, args.toArray());
        args.add(request.size());
        args.add(request.offset());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.public_id, w.public_id AS warehouse_public_id, w.warehouse_code,
                       l.public_id AS location_public_id, l.location_code,
                       i.public_id AS item_public_id, i.sku, i.item_name,
                       lot.public_id AS lot_public_id, lot.lot_number, b.stock_status,
                       b.on_hand_qty, b.reserved_qty, b.available_qty, b.version,
                       b.updated_at
                FROM inventory_balances b
                JOIN items i ON i.id = b.item_id AND i.tenant_id = b.tenant_id
                JOIN warehouses w ON w.id = b.warehouse_id AND w.tenant_id = b.tenant_id
                JOIN warehouse_locations l ON l.id = b.location_id AND l.warehouse_id = b.warehouse_id
                    AND l.tenant_id = b.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id = b.lot_id AND lot.tenant_id = b.tenant_id
                """ + where + " ORDER BY b.id LIMIT ? OFFSET ?", args.toArray());
        return pageResult(rows, total, request);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ledger(WmsAuthenticatedPrincipal principal, String itemPublicId,
                                      String warehousePublicId, String locationPublicId,
                                      String movementType, int page, int size) {
        Access access = access(principal, "WMS_INVENTORY_LEDGER_READ");
        Page request = page(page, size);
        StringBuilder where = new StringBuilder(" WHERE g.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(access.tenantId());
        addFilter(where, args, "i.public_id", itemPublicId);
        addFilter(where, args, "w.public_id", warehousePublicId);
        addFilter(where, args, "l.public_id", locationPublicId);
        addFilter(where, args, "g.movement_type", normalizeFilter(movementType,
                List.of("RECEIPT", "ISSUE", "TRANSFER", "ADJUSTMENT_IN", "ADJUSTMENT_OUT",
                        "RETURN", "DAMAGE", "STATUS_CHANGE", "OPENING_BALANCE")));
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_ledger g
                JOIN items i ON i.id = g.item_id AND i.tenant_id = g.tenant_id
                JOIN warehouses w ON w.id = g.warehouse_id AND w.tenant_id = g.tenant_id
                JOIN warehouse_locations l ON l.id = g.location_id AND l.warehouse_id = g.warehouse_id
                    AND l.tenant_id = g.tenant_id
                """ + where, Integer.class, args.toArray());
        args.add(request.size());
        args.add(request.offset());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT g.public_id, g.movement_group_id, g.request_id, g.entry_no, g.movement_type,
                       w.public_id AS warehouse_public_id, w.warehouse_code,
                       l.public_id AS location_public_id, l.location_code,
                       i.public_id AS item_public_id, i.sku, i.item_name,
                       lot.public_id AS lot_public_id, lot.lot_number,
                       s.public_id AS serial_public_id, s.serial_number, g.stock_status,
                       g.quantity_delta, g.balance_before_qty, g.balance_after_qty,
                       actor.public_id AS actor_membership_public_id, g.occurred_at, g.notes
                FROM inventory_ledger g
                JOIN items i ON i.id = g.item_id AND i.tenant_id = g.tenant_id
                JOIN warehouses w ON w.id = g.warehouse_id AND w.tenant_id = g.tenant_id
                LEFT JOIN tenant_memberships actor
                    ON actor.id = g.actor_membership_id AND actor.tenant_id = g.tenant_id
                JOIN warehouse_locations l ON l.id = g.location_id AND l.warehouse_id = g.warehouse_id
                    AND l.tenant_id = g.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id = g.lot_id AND lot.tenant_id = g.tenant_id
                LEFT JOIN inventory_serials s ON s.id = g.serial_id AND s.tenant_id = g.tenant_id
                """ + where + " ORDER BY g.occurred_at DESC, g.id DESC LIMIT ? OFFSET ?", args.toArray());
        return pageResult(rows, total, request);
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
        if (!resolved.hasPermission(permission)) throw new AccessDeniedException("Inventory read permission is required");
        return new Access(resolved.tenantId());
    }

    private static String normalizeFilter(String value, List<String> allowed) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException("Inventory filter is invalid");
        return normalized;
    }

    private static String normalizeFilter(String value, List<String> allowed) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException("Inventory filter value is invalid");
        return normalized;
    }
    private static void addFilter(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(column).append(" = ?");
            args.add(value.trim());
        }
    }

    private static Page page(int page, int size) {
        if (page < 0 || size < 1 || size > 200 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 200");
        }
        return new Page(page, size, page * size);
    }

    private static Map<String, Object> pageResult(List<Map<String, Object>> rows, Integer total, Page page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", rows);
        result.put("page", page.number());
        result.put("size", page.size());
        result.put("totalElements", total == null ? 0 : total);
        result.put("totalPages", total == null || total == 0 ? 0 : (int) Math.ceil((double) total / page.size()));
        return result;
    }

    private record Access(long tenantId) {}
    private record Page(int number, int size, int offset) {}
}