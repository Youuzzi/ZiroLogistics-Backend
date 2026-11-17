package com.zirocraft.zirologistics.wms.stockcount.application;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-scoped stock-count planning and snapshot foundation.
 *
 * Stock is frozen only after the warehouse and all scoped locations have been
 * locked. Every stock posting takes the same tenant/warehouse/location locks
 * before checking this service's freeze marker.
 */
@Service
@Profile("wms")
public class WmsStockCountManagementService {
    private static final Set<String> COUNT_TYPES = Set.of("FULL_COUNT", "CYCLE_COUNT", "SPOT_COUNT");
    private static final Set<String> COUNT_MODES = Set.of("BLIND", "OPEN");
    private static final Set<String> FROZEN_STATES = Set.of("IN_PROGRESS", "SUBMITTED", "UNDER_REVIEW", "APPROVED");

    private final WmsMembershipAccessService accessService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsStockCountManagementService(
            WmsMembershipAccessService accessService, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(WmsAuthenticatedPrincipal principal) {
        Access access = access(principal, "WMS_STOCK_COUNT_READ");
        return jdbc.queryForList("""
                SELECT c.public_id, c.document_number, c.count_type, c.count_mode, c.status,
                       c.freeze_stock, w.public_id AS warehouse_public_id, w.warehouse_code,
                       c.scheduled_at, c.started_at, c.submitted_at, c.reviewed_at,
                       c.approved_at, c.completed_at, c.notes, c.version, c.created_at, c.updated_at
                FROM stock_counts c
                JOIN warehouses w ON w.id = c.warehouse_id AND w.tenant_id = c.tenant_id
                WHERE c.tenant_id = ?
                ORDER BY c.created_at DESC, c.id DESC
                LIMIT 200
                """, access.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(WmsAuthenticatedPrincipal principal, String countPublicId) {
        Access access = access(principal, "WMS_STOCK_COUNT_READ");
        return view(access, countPublicId);
    }

    @Transactional
    public Map<String, Object> create(WmsAuthenticatedPrincipal principal, CreateCountCommand command) {
        if (command == null) throw new IllegalArgumentException("Stock count command is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_STOCK_COUNT_CREATE");

        String documentNumber = required(command.documentNumber(), "Document number", 60);
        String countType = enumValue(command.countType(), COUNT_TYPES, "count type");
        String countMode = enumValue(command.countMode(), COUNT_MODES, "count mode");
        String notes = optional(command.notes(), 1000);
        List<String> requestedLocations = normalizePublicIds(command.locationPublicIds());
        if (requestedLocations.isEmpty() || requestedLocations.size() > 100) {
            throw new IllegalArgumentException("A stock count must contain between 1 and 100 location scopes");
        }
        if (requestedLocations.stream().distinct().count() != requestedLocations.size()) {
            throw new IllegalArgumentException("Duplicate location scopes are not allowed");
        }

        Map<String, Object> warehouse = requiredRow("""
                SELECT id, public_id, warehouse_code, status
                FROM warehouses WHERE tenant_id = ? AND public_id = ?
                """, access.tenantId(), required(command.warehousePublicId(), "Warehouse public ID", 36));
        long warehouseId = number(warehouse, "id");
        lockIds("warehouses", List.of(warehouseId), access.tenantId(), "warehouse");
        warehouse = requiredRowForUpdate("""
                SELECT id, public_id, warehouse_code, status
                FROM warehouses WHERE id = ? AND tenant_id = ?
                """, warehouseId, access.tenantId());
        if (!"ACTIVE".equals(string(warehouse, "status"))) {
            throw new IllegalArgumentException("Warehouse must be active");
        }

        List<ScopeLocation> locations = resolveRequestedLocations(
                access.tenantId(), warehouseId, requestedLocations);
        lockIds("warehouse_locations", locations.stream().map(ScopeLocation::id).toList(),
                access.tenantId(), "location");
        validateActiveLocations(access.tenantId(), warehouseId, locations);

        String countPublicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO stock_counts
                      (public_id, tenant_id, warehouse_id, document_number, count_type, count_mode,
                       status, freeze_stock, created_by_membership_id, notes)
                    VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', 0, ?, ?)
                    """, countPublicId, access.tenantId(), warehouseId, documentNumber,
                    countType, countMode, access.membershipId(), notes);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Stock count document number already exists", duplicate);
        }
        long countId = requiredLong(
                "SELECT id FROM stock_counts WHERE tenant_id = ? AND public_id = ?",
                access.tenantId(), countPublicId);
        for (ScopeLocation location : locations) {
            jdbc.update("""
                    INSERT INTO stock_count_scopes
                      (tenant_id, stock_count_id, warehouse_id, location_id, scope_status)
                    VALUES (?, ?, ?, ?, 'PENDING')
                    """, access.tenantId(), countId, warehouseId, location.id());
        }
        audit(access, countPublicId, "NONE", "DRAFT", "CREATE", null);
        return view(access, countPublicId);
    }

    @Transactional
    public Map<String, Object> plan(WmsAuthenticatedPrincipal principal, String countPublicId) {
        lockTenant(principal);
        Access access = access(principal, "WMS_STOCK_COUNT_PLAN");
        Map<String, Object> header = header(access.tenantId(), countPublicId, false);
        List<ScopeLocation> scopes = lockWarehouseAndScopes(access, header, true);
        Map<String, Object> locked = header(access.tenantId(), countPublicId, true);
        if (!"DRAFT".equals(string(locked, "status"))) {
            throw new IllegalStateException("Only a draft stock count can be planned");
        }
        if (scopes.isEmpty()) throw new IllegalStateException("Stock count has no location scopes");

        int changed = jdbc.update("""
                UPDATE stock_counts
                SET status = 'PLANNED', version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status = 'DRAFT'
                """, number(locked, "id"), access.tenantId());
        if (changed != 1) throw new IllegalStateException("Stock count state changed; reload and retry");
        audit(access, countPublicId, "DRAFT", "PLANNED", "PLAN", null);
        return view(access, countPublicId);
    }

    @Transactional
    public Map<String, Object> start(WmsAuthenticatedPrincipal principal, String countPublicId) {
        lockTenant(principal);
        Access access = access(principal, "WMS_STOCK_COUNT_START");
        Map<String, Object> header = header(access.tenantId(), countPublicId, false);
        if (!"PLANNED".equals(string(header, "status"))) {
            throw new IllegalStateException("Only a planned stock count can be started");
        }
        List<ScopeLocation> scopes = lockWarehouseAndScopes(access, header, true);
        if (scopes.isEmpty()) throw new IllegalStateException("Stock count has no location scopes");
        long countId = number(header, "id");
        long warehouseId = number(header, "warehouse_id");
        List<Long> scopedLocationIds = scopes.stream().map(ScopeLocation::id).distinct().sorted().toList();
        String scopePlaceholders = String.join(",", java.util.Collections.nCopies(scopedLocationIds.size(), "?"));
        List<Object> overlapArgs = new ArrayList<>();
        overlapArgs.add(access.tenantId());
        overlapArgs.add(warehouseId);
        overlapArgs.add(countId);
        overlapArgs.addAll(scopedLocationIds);
        List<Long> overlappingCounts = jdbc.queryForList("""
                SELECT DISTINCT c.id
                FROM stock_counts c
                JOIN stock_count_scopes s ON s.stock_count_id = c.id AND s.tenant_id = c.tenant_id
                WHERE c.tenant_id = ? AND c.warehouse_id = ? AND c.id <> ?
                  AND c.freeze_stock = 1
                  AND c.status IN ('IN_PROGRESS','SUBMITTED','UNDER_REVIEW','APPROVED')
                  AND s.location_id IN (%s)
                ORDER BY c.id
                """.formatted(scopePlaceholders), Long.class, overlapArgs.toArray());
        if (!overlappingCounts.isEmpty()) {
            throw new IllegalStateException("One or more locations are already frozen by another active stock count");
        }
        Integer existingLines = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stock_count_lines WHERE tenant_id = ? AND stock_count_scope_id IN " +
                        "(SELECT id FROM stock_count_scopes WHERE tenant_id = ? AND stock_count_id = ?)",
                Integer.class, access.tenantId(), access.tenantId(), countId);
        if (existingLines != null && existingLines > 0) {
            throw new IllegalStateException("Stock count snapshot already exists");
        }

        List<Long> locationIds = scopes.stream().map(ScopeLocation::id).distinct().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(locationIds.size(), "?"));
        List<Object> queryArgs = new ArrayList<>();
        queryArgs.add(access.tenantId());
        queryArgs.add(warehouseId);
        queryArgs.addAll(locationIds);
        String bucketSql = """
                SELECT location_id, item_id, lot_id, stock_status
                FROM inventory_balances
                WHERE tenant_id = ? AND warehouse_id = ? AND location_id IN (%s)
                ORDER BY location_id, item_id, lot_id, stock_status
                """.formatted(placeholders);
        List<Map<String, Object>> buckets = jdbc.queryForList(bucketSql, queryArgs.toArray());

        List<Long> itemIds = buckets.stream().map(row -> number(row, "item_id")).distinct().sorted().toList();
        List<Long> lotIds = buckets.stream().map(row -> nullableNumber(row.get("lot_id")))
                .filter(Objects::nonNull).distinct().sorted().toList();
        lockIds("items", itemIds, access.tenantId(), "item");
        lockIds("inventory_lots", lotIds, access.tenantId(), "lot");

        List<Map<String, Object>> balances = jdbc.queryForList(
                "SELECT id, location_id, item_id, lot_id, stock_status, on_hand_qty " + bucketSql.substring(bucketSql.indexOf("FROM")) + " FOR UPDATE",
                queryArgs.toArray());
        Map<String, Object> locked = header(access.tenantId(), countPublicId, true);
        if (!"PLANNED".equals(string(locked, "status"))) {
            throw new IllegalStateException("Stock count state changed; reload and retry");
        }
        Map<Long, Long> scopeIds = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT id, location_id FROM stock_count_scopes
                WHERE tenant_id = ? AND stock_count_id = ? ORDER BY location_id
                """, access.tenantId(), countId).forEach(row ->
                scopeIds.put(number(row, "location_id"), number(row, "id")));

        for (Map<String, Object> balance : balances) {
            Long scopeId = scopeIds.get(number(balance, "location_id"));
            if (scopeId == null) throw new IllegalStateException("Stock count scope changed during snapshot");
            try {
                jdbc.update("""
                        INSERT INTO stock_count_lines
                          (public_id, tenant_id, stock_count_scope_id, warehouse_id, location_id,
                           item_id, lot_id, stock_status, expected_qty, line_status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                        """, UUID.randomUUID().toString(), access.tenantId(), scopeId, warehouseId,
                        number(balance, "location_id"), number(balance, "item_id"),
                        nullableNumber(balance.get("lot_id")), string(balance, "stock_status"),
                        decimal(balance.get("on_hand_qty")));
            } catch (DuplicateKeyException duplicate) {
                throw new IllegalStateException("Duplicate inventory bucket in stock count snapshot", duplicate);
            }
        }

        for (ScopeLocation scope : scopes) {
            int changed = jdbc.update("""
                    UPDATE stock_count_scopes
                    SET scope_status = 'IN_PROGRESS', started_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND tenant_id = ? AND stock_count_id = ? AND scope_status = 'PENDING'
                    """, scope.id(), access.tenantId(), countId);
            if (changed != 1) throw new IllegalStateException("Stock count scope changed; reload and retry");
        }
        int changed = jdbc.update("""
                UPDATE stock_counts
                SET status = 'IN_PROGRESS', freeze_stock = 1, started_at = UTC_TIMESTAMP(6),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status = 'PLANNED' AND freeze_stock = 0
                """, countId, access.tenantId());
        if (changed != 1) throw new IllegalStateException("Stock count state changed; reload and retry");

        audit(access, countPublicId, "PLANNED", "IN_PROGRESS", "START", Map.of(
                "freezeStock", true, "snapshotBucketCount", balances.size()));
        return view(access, countPublicId);
    }

    @Transactional
    public Map<String, Object> cancel(WmsAuthenticatedPrincipal principal, String countPublicId) {
        lockTenant(principal);
        Access access = access(principal, "WMS_STOCK_COUNT_CANCEL");
        Map<String, Object> header = header(access.tenantId(), countPublicId, false);
        List<ScopeLocation> scopes = lockWarehouseAndScopes(access, header, false);
        Map<String, Object> locked = header(access.tenantId(), countPublicId, true);
        String oldStatus = string(locked, "status");
        if (!Set.of("DRAFT", "PLANNED", "IN_PROGRESS").contains(oldStatus)) {
            throw new IllegalStateException("Submitted or reviewed stock counts cannot be cancelled");
        }
        long countId = number(locked, "id");
        jdbc.update("""
                UPDATE stock_count_lines SET line_status = 'CANCELLED', updated_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND stock_count_scope_id IN
                  (SELECT id FROM stock_count_scopes WHERE tenant_id = ? AND stock_count_id = ?)
                """, access.tenantId(), access.tenantId(), countId);
        jdbc.update("""
                UPDATE stock_count_scopes
                SET scope_status = 'CANCELLED', completed_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND stock_count_id = ?
                """, access.tenantId(), countId);
        int changed = jdbc.update("""
                UPDATE stock_counts
                SET status = 'CANCELLED', freeze_stock = 0, version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status = ? AND status IN ('DRAFT','PLANNED','IN_PROGRESS')
                """, countId, access.tenantId(), oldStatus);
        if (changed != 1) throw new IllegalStateException("Stock count state changed; reload and retry");
        audit(access, countPublicId, oldStatus, "CANCELLED", "CANCEL", Map.of(
                "freezeStock", false, "scopeCount", scopes.size()));
        return view(access, countPublicId);
    }

    private Map<String, Object> view(Access access, String countPublicId) {
        Map<String, Object> row = header(access.tenantId(), countPublicId, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publicId", string(row, "public_id"));
        result.put("documentNumber", string(row, "document_number"));
        result.put("warehousePublicId", string(row, "warehouse_public_id"));
        result.put("warehouseCode", string(row, "warehouse_code"));
        result.put("countType", string(row, "count_type"));
        result.put("countMode", string(row, "count_mode"));
        result.put("status", string(row, "status"));
        result.put("freezeStock", bool(row.get("freeze_stock")));
        result.put("scheduledAt", row.get("scheduled_at"));
        result.put("startedAt", row.get("started_at"));
        result.put("submittedAt", row.get("submitted_at"));
        result.put("reviewedAt", row.get("reviewed_at"));
        result.put("approvedAt", row.get("approved_at"));
        result.put("completedAt", row.get("completed_at"));
        result.put("notes", row.get("notes"));
        result.put("version", row.get("version"));
        result.put("createdAt", row.get("created_at"));
        result.put("updatedAt", row.get("updated_at"));

        List<Map<String, Object>> scopes = jdbc.queryForList("""
                SELECT l.public_id AS location_public_id, l.location_code, l.location_name,
                       s.scope_status, s.started_at, s.completed_at
                FROM stock_count_scopes s
                JOIN warehouse_locations l
                  ON l.id = s.location_id AND l.warehouse_id = s.warehouse_id AND l.tenant_id = s.tenant_id
                WHERE s.tenant_id = ? AND s.stock_count_id = ?
                ORDER BY l.location_code, l.id
                """, access.tenantId(), number(row, "id"));
        result.put("scopes", scopes);

        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT l.public_id, i.public_id AS item_public_id, i.sku, i.item_name,
                       wl.public_id AS location_public_id, wl.location_code,
                       lot.public_id AS lot_public_id, l.stock_status, l.expected_qty, l.line_status
                FROM stock_count_lines l
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                JOIN warehouse_locations wl
                  ON wl.id = l.location_id AND wl.warehouse_id = l.warehouse_id AND wl.tenant_id = l.tenant_id
                LEFT JOIN inventory_lots lot
                  ON lot.id = l.lot_id AND lot.tenant_id = l.tenant_id AND lot.item_id = l.item_id
                JOIN stock_count_scopes s
                  ON s.id = l.stock_count_scope_id AND s.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND s.stock_count_id = ?
                ORDER BY wl.location_code, i.sku, l.stock_status, l.id
                """, access.tenantId(), number(row, "id"));
        boolean exposeExpected = !"BLIND".equals(string(row, "count_mode")) || access.canReviewCount();
        List<Map<String, Object>> safeLines = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("publicId", line.get("public_id"));
            safe.put("itemPublicId", line.get("item_public_id"));
            safe.put("sku", line.get("sku"));
            safe.put("itemName", line.get("item_name"));
            safe.put("locationPublicId", line.get("location_public_id"));
            safe.put("locationCode", line.get("location_code"));
            if (line.get("lot_public_id") != null) safe.put("lotPublicId", line.get("lot_public_id"));
            safe.put("stockStatus", line.get("stock_status"));
            if (exposeExpected) safe.put("expectedQty", line.get("expected_qty"));
            safe.put("lineStatus", line.get("line_status"));
            safeLines.add(safe);
        }
        result.put("lines", safeLines);
        return result;
    }

    private List<ScopeLocation> lockWarehouseAndScopes(Access access, Map<String, Object> header, boolean requireActive) {
        long warehouseId = number(header, "warehouse_id");
        lockIds("warehouses", List.of(warehouseId), access.tenantId(), "warehouse");
        Map<String, Object> warehouse = requiredRowForUpdate(
                "SELECT status FROM warehouses WHERE id = ? AND tenant_id = ?", warehouseId, access.tenantId());
        if (requireActive && !"ACTIVE".equals(string(warehouse, "status"))) {
            throw new IllegalStateException("Stock count warehouse is no longer active");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.location_id, l.public_id, l.status
                FROM stock_count_scopes s
                JOIN warehouse_locations l
                  ON l.id = s.location_id AND l.warehouse_id = s.warehouse_id AND l.tenant_id = s.tenant_id
                WHERE s.tenant_id = ? AND s.stock_count_id = ?
                ORDER BY s.location_id
                """, access.tenantId(), number(header, "id"));
        List<ScopeLocation> scopes = rows.stream()
                .map(row -> new ScopeLocation(number(row, "location_id"),
                        string(row, "public_id"), string(row, "status")))
                .toList();
        lockIds("warehouse_locations", scopes.stream().map(ScopeLocation::id).toList(),
                access.tenantId(), "location");
        if (requireActive) validateActiveLocations(access.tenantId(), warehouseId, scopes);
        return scopes;
    }

    private List<ScopeLocation> resolveRequestedLocations(long tenantId, long warehouseId, List<String> publicIds) {
        List<ScopeLocation> result = new ArrayList<>();
        for (String publicId : publicIds) {
            Map<String, Object> row = requiredRow("""
                    SELECT id, public_id, status FROM warehouse_locations
                    WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ?
                    """, tenantId, warehouseId, publicId);
            result.add(new ScopeLocation(number(row, "id"), string(row, "public_id"), string(row, "status")));
        }
        return result.stream().sorted(Comparator.comparingLong(ScopeLocation::id)).toList();
    }

    private void validateActiveLocations(long tenantId, long warehouseId, List<ScopeLocation> locations) {
        for (ScopeLocation location : locations) {
            Map<String, Object> row = requiredRowForUpdate("""
                    SELECT status FROM warehouse_locations
                    WHERE id = ? AND tenant_id = ? AND warehouse_id = ?
                    """, location.id(), tenantId, warehouseId);
            if (!"ACTIVE".equals(string(row, "status"))) {
                throw new IllegalArgumentException("All stock count locations must be active");
            }
        }
    }

    private Map<String, Object> header(long tenantId, String publicId, boolean forUpdate) {
        String sql = """
                SELECT c.id, c.public_id, c.tenant_id, c.warehouse_id, c.document_number,
                       c.count_type, c.count_mode, c.status, c.freeze_stock, c.scheduled_at,
                       c.started_at, c.submitted_at, c.reviewed_at, c.approved_at, c.completed_at,
                       c.created_by_membership_id, c.reviewed_by_membership_id, c.approved_by_membership_id,
                       c.notes, c.version, c.created_at, c.updated_at,
                       w.public_id AS warehouse_public_id, w.warehouse_code
                FROM stock_counts c
                JOIN warehouses w ON w.id = c.warehouse_id AND w.tenant_id = c.tenant_id
                WHERE c.tenant_id = ? AND c.public_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return requiredRow(sql, tenantId, required(publicId, "Stock count public ID", 36));
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
        if (!resolved.hasPermission(permission)) throw new AccessDeniedException("Required stock count permission is missing");
        return new Access(resolved.tenantId(), resolved.membershipId(), resolved.userPublicId(),
                resolved.hasPermission("WMS_STOCK_COUNT_REVIEW")
                        || resolved.hasPermission("WMS_STOCK_COUNT_APPROVE"));
    }

    private void lockTenant(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                Long.class, principal.tenantId());
        if (ids.isEmpty()) throw new AccessDeniedException("Active tenant is unavailable");
    }

    private void lockIds(String table, List<Long> ids, long tenantId, String label) {
        if (!Set.of("warehouses", "warehouse_locations", "items", "inventory_lots").contains(table)) {
            throw new IllegalArgumentException("Unsupported lock table");
        }
        ids.stream().filter(Objects::nonNull).distinct().sorted().forEach(id -> {
            List<Long> rows = jdbc.queryForList(
                    "SELECT id FROM " + table + " WHERE id = ? AND tenant_id = ? FOR UPDATE",
                    Long.class, id, tenantId);
            if (rows.isEmpty()) throw new IllegalArgumentException("Referenced " + label + " is unavailable");
        });
    }

    private void audit(Access access, String countPublicId, String oldStatus, String newStatus,
                       String action, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("oldStatus", oldStatus);
        payload.put("newStatus", newStatus);
        if (details != null) payload.putAll(details);
        final String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stock count audit payload could not be serialized", failure);
        }
        int written = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, operation_ref,
                   event_type, subject_public_id, change_payload)
                SELECT ?, t.id, m.id, 'TENANT_MEMBER', ?, 'WMS_STOCK_COUNT_UPDATED', ?, ?
                FROM tenants t
                JOIN tenant_memberships m ON m.id = ? AND m.tenant_id = t.id AND m.status = 'ACTIVE'
                JOIN users u ON u.id = m.user_id AND u.public_id = ? AND u.status = 'ACTIVE'
                WHERE t.id = ? AND t.status = 'ACTIVE'
                """, UUID.randomUUID().toString(), countPublicId, countPublicId, json,
                access.membershipId(), access.userPublicId(), access.tenantId());
        if (written != 1) throw new AccessDeniedException("Audit actor is no longer active");
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new IllegalArgumentException("Stock count or referenced record not found");
        return rows.getFirst();
    }

    private Map<String, Object> requiredRowForUpdate(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql + " FOR UPDATE", args);
        if (rows.isEmpty()) throw new IllegalArgumentException("Referenced record is no longer available");
        return rows.getFirst();
    }

    private long requiredLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        if (value == null) throw new IllegalStateException("Required stock count record was not created");
        return value;
    }

    private static List<String> normalizePublicIds(List<String> ids) {
        if (ids == null) return List.of();
        return ids.stream().map(id -> required(id, "Location public ID", 36)).toList();
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value, label, 30).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new IllegalArgumentException("Unsupported " + label);
        return normalized;
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("Notes are too long");
        return normalized;
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static Long nullableNumber(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static String string(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }

    public record CreateCountCommand(String warehousePublicId, String documentNumber,
                                     String countType, String countMode,
                                     List<String> locationPublicIds, String notes) {}

    private record Access(long tenantId, long membershipId, String userPublicId, boolean canReviewCount) {}
    private record ScopeLocation(long id, String publicId, String status) {}
}
