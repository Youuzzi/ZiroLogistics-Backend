package com.zirocraft.zirologistics.wms.inventory.application;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Internal, tenant-scoped inventory posting boundary.
 *
 * This first implementation deliberately supports non-serial, single-direction
 * posting types. Paired movements and serial lifecycle transitions are added
 * by the next inventory-posting increment; unsupported tracking is rejected.
 */
@Service
@Profile("wms")
public class WmsInventoryPostingService {
    private static final Set<String> POSITIVE_MOVEMENTS =
            Set.of("RECEIPT", "RETURN", "OPENING_BALANCE", "ADJUSTMENT_IN");
    private static final Set<String> NEGATIVE_MOVEMENTS =
            Set.of("ISSUE", "ADJUSTMENT_OUT");
    private static final Set<String> STOCK_STATUSES =
            Set.of("AVAILABLE", "QUARANTINED", "DAMAGED", "EXPIRED", "BLOCKED");

    private final WmsMembershipAccessService accessService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsInventoryPostingService(
            WmsMembershipAccessService accessService, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional
    public Map<String, Object> post(WmsAuthenticatedPrincipal principal, PostingCommand command) {
        Access access = access(principal);
        NormalizedCommand normalized = normalize(command);

        // This tenant lock is the shared serialization point with Stage 7
        // warehouse/location lifecycle operations.
        lockTenant(access.tenantId());
        if (requestAlreadyUsed(access.tenantId(), normalized.requestId())) {
            throw new IllegalStateException("Inventory posting request ID has already been used");
        }

        List<ResolvedLeg> legs = resolveAndLockReferences(access.tenantId(), normalized.legs());
        List<Bucket> buckets = legs.stream().map(ResolvedLeg::bucket).distinct().sorted().toList();
        Map<Bucket, Balance> balances = lockOrCreateBalances(access.tenantId(), buckets);

        String movementGroupId = UUID.randomUUID().toString();
        List<Map<String, Object>> auditEntries = new ArrayList<>();
        int entryNo = 0;
        for (ResolvedLeg leg : legs) {
            entryNo++;
            Balance balance = balances.get(leg.bucket());
            BigDecimal delta = POSITIVE_MOVEMENTS.contains(normalized.movementType())
                    ? leg.quantity() : leg.quantity().negate();
            BigDecimal before = balance.onHand();
            BigDecimal after = before.add(delta);
            if (after.signum() < 0 || after.precision() > 19) {
                throw new IllegalStateException("Insufficient stock or inventory quantity overflow");
            }
            int changed = jdbc.update("""
                    UPDATE inventory_balances
                    SET on_hand_qty = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND tenant_id = ? AND on_hand_qty = ?
                      AND reserved_qty <= ?
                    """, after, balance.id(), access.tenantId(), before, after);
            if (changed != 1) {
                throw new IllegalStateException("Inventory balance changed or reserved stock would be invalid");
            }
            try {
                jdbc.update("""
                        INSERT INTO inventory_ledger
                          (public_id, tenant_id, movement_group_id, request_id, entry_no,
                           movement_type, warehouse_id, location_id, item_id, lot_id, serial_id,
                           stock_status, quantity_delta, balance_before_qty, balance_after_qty,
                           reference_type, reference_id, reference_line_id, actor_membership_id, notes)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID().toString(), access.tenantId(), movementGroupId,
                        normalized.requestId(), entryNo, normalized.movementType(),
                        leg.warehouseId(), leg.locationId(), leg.itemId(), leg.lotId(),
                        leg.stockStatus(), delta, before, after, normalized.referenceType(),
                        normalized.referenceId(), leg.referenceLineId(), access.membershipId(),
                        normalized.notes());
            } catch (DuplicateKeyException duplicate) {
                throw new IllegalStateException("Inventory posting request ID has already been used");
            }
            balance = new Balance(balance.id(), after);
            balances.put(leg.bucket(), balance);
            auditEntries.add(Map.of(
                    "item", leg.itemPublicId(),
                    "warehouse", leg.warehousePublicId(),
                    "location", leg.locationPublicId(),
                    "quantityDelta", delta.toPlainString(),
                    "stockStatus", leg.stockStatus()));
        }

        writeAudit(access, movementGroupId, normalized.requestId(),
                normalized.movementType(), auditEntries);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("movementGroupPublicId", movementGroupId);
        result.put("requestId", normalized.requestId());
        result.put("movementType", normalized.movementType());
        result.put("entryCount", entryNo);
        result.put("status", "POSTED");
        return Map.copyOf(result);
    }

    private List<ResolvedLeg> resolveAndLockReferences(long tenantId, List<Leg> requested) {
        Map<String, Map<String, Object>> warehouses = new HashMap<>();
        Map<String, Map<String, Object>> locations = new HashMap<>();
        Map<String, Map<String, Object>> items = new HashMap<>();
        Map<String, Map<String, Object>> lots = new HashMap<>();

        for (Leg leg : requested) {
            warehouses.computeIfAbsent(leg.warehousePublicId(), key -> one("""
                    SELECT id, public_id, status FROM warehouses
                    WHERE tenant_id = ? AND public_id = ?
                    """, tenantId, key, "Warehouse"));
            locations.computeIfAbsent(leg.locationPublicId(), key -> one("""
                    SELECT id, public_id, warehouse_id, status FROM warehouse_locations
                    WHERE tenant_id = ? AND public_id = ?
                    """, tenantId, key, "Warehouse location"));
            items.computeIfAbsent(leg.itemPublicId(), key -> one("""
                    SELECT id, public_id, tracking_method, is_stock_item, status
                    FROM items WHERE tenant_id = ? AND public_id = ?
                    """, tenantId, key, "Item"));
            if (leg.lotPublicId() != null) {
                lots.computeIfAbsent(leg.lotPublicId(), key -> one("""
                        SELECT id, public_id, item_id, status FROM inventory_lots
                        WHERE tenant_id = ? AND public_id = ?
                        """, tenantId, key, "Inventory lot"));
            }
        }

        lockRows("warehouses", warehouses.values(), tenantId);
        lockRows("warehouse_locations", locations.values(), tenantId);
        lockRows("items", items.values(), tenantId);
        lockRows("inventory_lots", lots.values(), tenantId);

        List<ResolvedLeg> resolved = new ArrayList<>();
        for (Leg leg : requested) {
            Map<String, Object> warehouse = warehouses.get(leg.warehousePublicId());
            Map<String, Object> location = locations.get(leg.locationPublicId());
            Map<String, Object> item = items.get(leg.itemPublicId());
            Map<String, Object> lot = leg.lotPublicId() == null ? null : lots.get(leg.lotPublicId());

            requireActive(warehouse, "Warehouse");
            requireActive(location, "Warehouse location");
            requireActive(item, "Item");
            if (!Objects.equals(number(location, "warehouse_id"), number(warehouse, "id"))) {
                throw new IllegalArgumentException("Location does not belong to the selected warehouse");
            }
            if (!Boolean.TRUE.equals(bool(item.get("is_stock_item")))) {
                throw new IllegalArgumentException("Non-stock items cannot be posted");
            }
            String tracking = (String) item.get("tracking_method");
            if ("SERIAL".equals(tracking) || "LOT_AND_SERIAL".equals(tracking)) {
                throw new IllegalArgumentException("Serial-tracked inventory requires the serial posting workflow");
            }
            if ("LOT".equals(tracking) != (lot != null)) {
                throw new IllegalArgumentException("Lot reference does not match the item's tracking method");
            }
            Long lotId = null;
            if (lot != null) {
                requireActive(lot, "Inventory lot");
                if (!Objects.equals(number(lot, "item_id"), number(item, "id"))) {
                    throw new IllegalArgumentException("Lot does not belong to the selected item");
                }
                lotId = number(lot, "id");
            }
            resolved.add(new ResolvedLeg(
                    leg.warehousePublicId(), leg.locationPublicId(), leg.itemPublicId(),
                    number(warehouse, "id"), number(location, "id"), number(item, "id"),
                    lotId, leg.stockStatus(), leg.quantity(), leg.referenceLineId()));
        }
        return resolved;
    }

    private void lockRows(String table, java.util.Collection<Map<String, Object>> rows, long tenantId) {
        if (rows.isEmpty()) return;
        List<Long> ids = rows.stream().map(row -> number(row, "id")).distinct().sorted().toList();
        String placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        List<Long> locked = jdbc.queryForList(
                "SELECT id FROM " + table + " WHERE tenant_id = ? AND id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
                Long.class, prepend(tenantId, ids));
        if (!locked.equals(ids)) throw new IllegalStateException("Inventory reference changed during posting");
    }

    private Map<Bucket, Balance> lockOrCreateBalances(long tenantId, List<Bucket> buckets) {
        Map<Bucket, Balance> result = new LinkedHashMap<>();
        for (Bucket bucket : buckets) {
            jdbc.update("""
                    INSERT INTO inventory_balances
                      (public_id, tenant_id, warehouse_id, location_id, item_id, lot_id, stock_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                    UUID.randomUUID().toString(), tenantId, bucket.warehouseId(), bucket.locationId(),
                    bucket.itemId(), bucket.lotId(), bucket.stockStatus());
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, on_hand_qty FROM inventory_balances
                    WHERE tenant_id = ? AND warehouse_id = ? AND location_id = ?
                      AND item_id = ? AND lot_key = COALESCE(?, 0) AND stock_status = ?
                    FOR UPDATE
                    """, tenantId, bucket.warehouseId(), bucket.locationId(),
                    bucket.itemId(), bucket.lotId(), bucket.stockStatus());
            if (rows.size() != 1) throw new IllegalStateException("Inventory balance bucket could not be locked");
            Map<String, Object> row = rows.getFirst();
            result.put(bucket, new Balance(number(row, "id"), (BigDecimal) row.get("on_hand_qty")));
        }
        return result;
    }

    private void writeAudit(
            Access access, String movementGroupId, String requestId,
            String movementType, List<Map<String, Object>> entries) {
        Map<String, Object> payload = Map.of(
                "movementType", movementType,
                "entryCount", entries.size(),
                "entries", entries);
        final String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Inventory audit payload could not be serialized");
        }
        int written = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, operation_ref,
                   event_type, subject_public_id, change_payload)
                SELECT ?, t.id, m.id, 'TENANT_MEMBER', ?, 'INVENTORY_MOVEMENT_POSTED', ?, ?
                FROM tenants t
                JOIN tenant_memberships m ON m.id = ? AND m.tenant_id = t.id AND m.status = 'ACTIVE'
                JOIN users u ON u.id = m.user_id AND u.public_id = ? AND u.status = 'ACTIVE'
                WHERE t.id = ? AND t.status = 'ACTIVE'
                """,
                UUID.randomUUID().toString(), requestId, movementGroupId, json,
                access.membershipId(), access.userPublicId(), access.tenantId());
        if (written != 1) throw new AccessDeniedException("Inventory audit actor is no longer active");
    }

    private boolean requestAlreadyUsed(long tenantId, String requestId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ? AND request_id = ?
                """, Integer.class, tenantId, requestId);
        return count != null && count > 0;
    }

    private Map<String, Object> one(String sql, Object first, Object second, String label) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, first, second);
        if (rows.size() != 1) throw new IllegalArgumentException(label + " not found in the active tenant");
        return rows.getFirst();
    }

    private void lockTenant(long tenantId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                Long.class, tenantId);
        if (rows.isEmpty()) throw new AccessDeniedException("Active tenant context is unavailable");
    }

    private Access access(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess resolved = accessService.resolve(
                principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(resolved.userPublicId(), principal.userPublicId())
                || !Objects.equals(resolved.tenantId(), principal.tenantId())
                || !Objects.equals(resolved.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(resolved.membershipId(), principal.membershipId())
                || !Objects.equals(resolved.membershipPublicId(), principal.membershipPublicId())) {
            throw new AccessDeniedException("Tenant membership context is unavailable");
        }
        if (!resolved.hasPermission("WMS_INVENTORY_POST")) {
            throw new AccessDeniedException("Inventory posting permission is required");
        }
        return new Access(resolved.tenantId(), resolved.membershipId(), resolved.userPublicId());
    }

    private static NormalizedCommand normalize(PostingCommand command) {
        Objects.requireNonNull(command, "posting command is required");
        String requestId;
        try {
            requestId = UUID.fromString(required(command.requestId(), "request ID")).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Request ID must be a UUID");
        }
        String movement = required(command.movementType(), "movement type").toUpperCase(java.util.Locale.ROOT);
        if (!POSITIVE_MOVEMENTS.contains(movement) && !NEGATIVE_MOVEMENTS.contains(movement)) {
            throw new IllegalArgumentException("Movement type is not supported by this posting operation");
        }
        if (command.legs() == null || command.legs().isEmpty() || command.legs().size() > 100) {
            throw new IllegalArgumentException("Posting must contain between 1 and 100 legs");
        }
        String referenceType = optional(command.referenceType(), 50);
        if (command.referenceId() != null && command.referenceId() <= 0) {
            throw new IllegalArgumentException("Reference ID must be positive");
        }
        if ((referenceType == null) != (command.referenceId() == null)) {
            throw new IllegalArgumentException("Reference type and reference ID must be supplied together");
        }
        String notes = optional(command.notes(), 1000);
        Set<String> buckets = new HashSet<>();
        List<Leg> legs = new ArrayList<>();
        for (LegCommand leg : command.legs()) {
            Objects.requireNonNull(leg, "posting leg is required");
            String warehouse = uuid(leg.warehousePublicId(), "warehouse public ID");
            String location = uuid(leg.locationPublicId(), "location public ID");
            String item = uuid(leg.itemPublicId(), "item public ID");
            String lot = leg.lotPublicId() == null || leg.lotPublicId().isBlank()
                    ? null : uuid(leg.lotPublicId(), "lot public ID");
            String status = required(leg.stockStatus(), "stock status").toUpperCase(java.util.Locale.ROOT);
            if (!STOCK_STATUSES.contains(status)) throw new IllegalArgumentException("Stock status is invalid");
            BigDecimal quantity;
            try {
                quantity = Objects.requireNonNull(leg.quantity(), "quantity is required")
                        .setScale(4, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException invalidScale) {
                throw new IllegalArgumentException("Quantity must be exactly representable at scale 4");
            }
            if (quantity.signum() <= 0 || quantity.precision() > 19) {
                throw new IllegalArgumentException("Quantity must be positive and fit DECIMAL(19,4)");
            }
            String bucket = warehouse + "|" + location + "|" + item + "|" + lot + "|" + status;
            if (!buckets.add(bucket)) throw new IllegalArgumentException("Duplicate inventory balance bucket in posting");
            Long lineId = leg.referenceLineId();
            if (lineId != null && lineId <= 0) throw new IllegalArgumentException("Reference line ID must be positive");
            legs.add(new Leg(warehouse, location, item, lot, status, quantity, lineId));
        }
        return new NormalizedCommand(requestId, movement, List.copyOf(legs),
                referenceType, command.referenceId(), notes);
    }

    private static String uuid(String value, String field) {
        try {
            return UUID.fromString(required(value, field)).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException("Optional text exceeds its maximum length");
        return normalized;
    }

    private static void requireActive(Map<String, Object> row, String label) {
        if (!"ACTIVE".equals(row.get("status"))) throw new IllegalStateException(label + " is not active");
    }

    private static Long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) return number.longValue();
        throw new IllegalStateException("Database reference is missing " + key);
    }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return null;
    }

    private static Object[] prepend(Object first, List<Long> values) {
        Object[] result = new Object[values.size() + 1];
        result[0] = first;
        for (int i = 0; i < values.size(); i++) result[i + 1] = values.get(i);
        return result;
    }

    public record PostingCommand(
            String requestId, String movementType, List<LegCommand> legs,
            String referenceType, Long referenceId, String notes) {}

    public record LegCommand(
            String warehousePublicId, String locationPublicId, String itemPublicId,
            String lotPublicId, String stockStatus, BigDecimal quantity, Long referenceLineId) {}

    private record Access(long tenantId, long membershipId, String userPublicId) {}
    private record NormalizedCommand(
            String requestId, String movementType, List<Leg> legs,
            String referenceType, Long referenceId, String notes) {}
    private record Leg(
            String warehousePublicId, String locationPublicId, String itemPublicId,
            String lotPublicId, String stockStatus, BigDecimal quantity, Long referenceLineId) {}
    private record ResolvedLeg(
            String warehousePublicId, String locationPublicId, String itemPublicId,
            long warehouseId, long locationId, long itemId, Long lotId,
            String stockStatus, BigDecimal quantity, Long referenceLineId) {
        Bucket bucket() {
            return new Bucket(warehouseId, locationId, itemId, lotId, stockStatus);
        }
    }
    private record Bucket(long warehouseId, long locationId, long itemId, Long lotId, String stockStatus)
            implements Comparable<Bucket> {
        @Override public int compareTo(Bucket other) {
            int c = Long.compare(warehouseId, other.warehouseId);
            if (c == 0) c = Long.compare(locationId, other.locationId);
            if (c == 0) c = Long.compare(itemId, other.itemId);
            if (c == 0) c = Comparator.nullsFirst(Long::compareTo).compare(lotId, other.lotId);
            if (c == 0) c = stockStatus.compareTo(other.stockStatus);
            return c;
        }
    }
    private record Balance(long id, BigDecimal onHand) {}
}
