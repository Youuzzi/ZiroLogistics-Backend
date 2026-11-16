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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The sole WMS application boundary for inventory balance, ledger and serial writes.
 * Workflow modules must submit typed commands here; they must never write stock tables directly.
 */
@Service
@Profile("wms")
public class WmsInventoryPostingService {
    private static final Set<String> MOVEMENTS = Set.of(
            "RECEIPT", "ISSUE", "TRANSFER", "ADJUSTMENT_IN", "ADJUSTMENT_OUT",
            "RETURN", "DAMAGE", "STATUS_CHANGE", "OPENING_BALANCE");
    private static final Set<String> STOCK_STATUSES =
            Set.of("AVAILABLE", "QUARANTINED", "DAMAGED", "EXPIRED", "BLOCKED");
    private static final Set<String> SERIAL_STATUSES =
            Set.of("AVAILABLE", "RESERVED", "PICKED", "SHIPPED", "DAMAGED",
                    "QUARANTINED", "RETURNED", "LOST");

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
        lockTenant(access.tenantId());

        // Resolve and lock reference rows in the shared warehouse -> location -> item -> lot -> serial order.
        List<ResolvedLeg> legs = resolveAndLockReferences(access.tenantId(), normalized);
        validateMovement(normalized, legs);
        String movementGroupId = UUID.randomUUID().toString();
        List<Map<String, Object>> entries = new ArrayList<>();
        int entryNo = 1;
        Map<String, Balance> lockedBalances = new HashMap<>();
        legs.stream().sorted(Comparator.comparingLong(ResolvedLeg::warehouseId)
                        .thenComparing(ResolvedLeg::locationId)
                        .thenComparing(ResolvedLeg::itemId)
                        .thenComparing(ResolvedLeg::lotId, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ResolvedLeg::stockStatus))
                .forEach(leg -> lockedBalances.put(balanceKey(leg), lockOrCreateBalance(access.tenantId(), leg)));

        for (ResolvedLeg leg : legs) {
            if (leg.serialId() == null && leg.serialNumber() != null) {
                leg = registerSerial(access.tenantId(), leg);
            }
            Balance balance = lockedBalances.get(balanceKey(leg));
            BigDecimal delta = signedDelta(normalized.movementType(), leg.index(), leg.quantity());
            BigDecimal before = balance.onHand();
            BigDecimal after = before.add(delta).setScale(4, RoundingMode.UNNECESSARY);
            if (after.signum() < 0) throw new IllegalStateException("Insufficient on-hand inventory");
            if (delta.signum() < 0 && before.subtract(balance.reserved()).compareTo(delta.abs()) < 0) {
                throw new IllegalStateException("Insufficient available inventory");
            }
            updateBalance(balance.id(), delta);
            insertLedger(access, normalized, movementGroupId, entryNo++, leg, delta, before, after);
            transitionSerial(normalized.movementType(), legs, leg);
            entries.add(Map.of(
                    "entryNo", entryNo - 1,
                    "itemPublicId", leg.itemPublicId(),
                    "warehousePublicId", leg.warehousePublicId(),
                    "locationPublicId", leg.locationPublicId(),
                    "quantityDelta", delta,
                    "balanceBeforeQty", before,
                    "balanceAfterQty", after));
        }

        writeAudit(access, normalized, movementGroupId, entries.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("movementGroupId", movementGroupId);
        result.put("requestId", normalized.requestId().toString());
        result.put("movementType", normalized.movementType());
        result.put("entryCount", entries.size());
        result.put("entries", entries);
        return result;
    }

    private List<ResolvedLeg> resolveAndLockReferences(long tenantId, NormalizedCommand command) {
        List<ResolvedLeg> resolved = new ArrayList<>();
        for (int i = 0; i < command.legs().size(); i++) {
            PostingLeg leg = command.legs().get(i);
            Map<String, Object> warehouse = requiredRow("SELECT id, status FROM warehouses WHERE tenant_id = ? AND public_id = ?",
                    tenantId, leg.warehousePublicId(), "Warehouse not found");
            resolved.add(new ResolvedLeg(i, leg, number(warehouse, "id"), string(warehouse, "status")));
        }
        lockIds("warehouses", resolved.stream().map(ResolvedLeg::warehouseId).toList(), tenantId, "warehouse");
        resolved = resolved.stream().map(leg -> {
            Map<String, Object> location = requiredRow(
                    "SELECT id, status FROM warehouse_locations WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ?",
                    tenantId, leg.warehouseId(), leg.locationPublicId(), "Location not found");
            return leg.withLocation(number(location, "id"), string(location, "status"));
        }).toList();
        lockIds("warehouse_locations", resolved.stream().map(ResolvedLeg::locationId).toList(), tenantId, "location");
        resolved = resolved.stream().map(leg -> {
            Map<String, Object> item = requiredRow("""
                    SELECT id, public_id, tracking_method, is_stock_item, status
                    FROM items WHERE tenant_id = ? AND public_id = ?
                    """, tenantId, leg.itemPublicId(), "Item not found");
            return leg.withItem(number(item, "id"), string(item, "tracking_method"),
                    bool(item.get("is_stock_item")), string(item, "status"));
        }).toList();
        lockIds("items", resolved.stream().map(ResolvedLeg::itemId).toList(), tenantId, "item");
        resolved = resolved.stream().map(leg -> {
            Long lotId = null;
            if (leg.lotPublicId() != null) {
                lotId = requiredId("""
                        SELECT id FROM inventory_lots
                        WHERE tenant_id = ? AND item_id = ? AND public_id = ? AND status = 'ACTIVE'
                        """, tenantId, leg.itemId(), leg.lotPublicId(), "Active lot not found");
            }
            return leg.withLot(lotId);
        }).toList();
        lockIds("inventory_lots", resolved.stream().map(ResolvedLeg::lotId)
                .filter(Objects::nonNull).toList(), tenantId, "lot");
        resolved = resolveSerials(tenantId, resolved);
        return resolved;
    }

    private List<ResolvedLeg> resolveSerials(long tenantId, List<ResolvedLeg> legs) {
        List<ResolvedLeg> result = new ArrayList<>();
        for (ResolvedLeg leg : legs) {
            Long serialId = null;
            String serialStatus = null;
            Long serialWarehouse = null;
            Long serialLocation = null;
            if (leg.serialPublicId() != null) {
                Map<String, Object> serial = requiredRow("""
                        SELECT id, status, current_warehouse_id, current_location_id
                        FROM inventory_serials
                        WHERE tenant_id = ? AND item_id = ? AND public_id = ? AND lot_id <=> ?
                        """, tenantId, leg.itemId(), leg.serialPublicId(), leg.lotId());
                serialId = number(serial, "id");
                serialStatus = string(serial, "status");
                serialWarehouse = nullableNumber(serial.get("current_warehouse_id"));
                serialLocation = nullableNumber(serial.get("current_location_id"));
            }
            result.add(leg.withSerial(serialId, serialStatus, serialWarehouse, serialLocation));
        }
        lockIds("inventory_serials", result.stream().map(ResolvedLeg::serialId)
                .filter(Objects::nonNull).toList(), tenantId, "serial");
        return result;
    }

    private void validateMovement(NormalizedCommand command, List<ResolvedLeg> legs) {
        String type = command.movementType();
        boolean paired = Set.of("TRANSFER", "DAMAGE", "STATUS_CHANGE").contains(type);
        if (paired && legs.size() != 2) throw new IllegalArgumentException(type + " requires exactly two legs");
        if (!paired && legs.isEmpty()) throw new IllegalArgumentException("At least one posting leg is required");
        Set<String> buckets = new HashSet<>();
        for (ResolvedLeg leg : legs) {
            if (!"ACTIVE".equals(leg.itemStatus()) || !leg.stockItem()) {
                throw new IllegalArgumentException("Only active stock items can be posted");
            }
            if (!STOCK_STATUSES.contains(leg.stockStatus())) throw new IllegalArgumentException("Stock status is invalid");
            if ("ISSUE".equals(type) && !"AVAILABLE".equals(leg.stockStatus())) {
                throw new IllegalArgumentException("Issue can only consume AVAILABLE stock");
            }
            if (!Set.of("ACTIVE").contains(leg.warehouseStatus()) || !"ACTIVE".equals(leg.locationStatus())) {
                throw new IllegalArgumentException("Warehouse and location must be active");
            }
            boolean lotTracked = Set.of("LOT", "LOT_AND_SERIAL").contains(leg.trackingMethod());
            boolean serialTracked = Set.of("SERIAL", "LOT_AND_SERIAL").contains(leg.trackingMethod());
            if (lotTracked != (leg.lotId() != null)) throw new IllegalArgumentException("Lot assignment does not match item tracking method");
            if (serialTracked != (leg.serialPublicId() != null || leg.serialNumber() != null)) {
                throw new IllegalArgumentException("Serial assignment does not match item tracking method");
            }
            if (serialTracked && leg.quantity().compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalArgumentException("Serial-tracked posting quantity must equal one");
            }
            String bucket = leg.warehouseId() + ":" + leg.locationId() + ":" + leg.itemId()
                    + ":" + leg.lotId() + ":" + leg.stockStatus();
            if (!buckets.add(bucket)) throw new IllegalArgumentException("Duplicate balance bucket in one posting");
        }
        if (paired) {
            ResolvedLeg source = legs.get(0);
            ResolvedLeg destination = legs.get(1);
            if (!source.itemId().equals(destination.itemId())
                    || !Objects.equals(source.lotId(), destination.lotId())
                    || source.quantity().compareTo(destination.quantity()) != 0
                    || !Objects.equals(source.serialId(), destination.serialId())) {
                throw new IllegalArgumentException("Paired movement legs must match item, lot, serial and quantity");
            }
            if ("TRANSFER".equals(type)
                    && source.warehouseId() == destination.warehouseId()
                    && source.locationId() == destination.locationId()) {
                throw new IllegalArgumentException("Transfer must change physical location");
            }
            if ("TRANSFER".equals(type) && !source.stockStatus().equals(destination.stockStatus())) {
                throw new IllegalArgumentException("Transfer must preserve stock status");
            }
            if ("STATUS_CHANGE".equals(type)
                    && (source.warehouseId() != destination.warehouseId()
                    || source.locationId() != destination.locationId())) {
                throw new IllegalArgumentException("Status change cannot change physical location");
            }
            if ("DAMAGE".equals(type)
                    && (!"AVAILABLE".equals(source.stockStatus()) || !"DAMAGED".equals(destination.stockStatus()))) {
                throw new IllegalArgumentException("Damage must move AVAILABLE stock to DAMAGED");
            }
            if ("STATUS_CHANGE".equals(type) && source.stockStatus().equals(destination.stockStatus())) {
                throw new IllegalArgumentException("Status change must change stock status");
            }
        }
        for (ResolvedLeg leg : legs) {
            if (leg.serialId() != null) {
                String expectedSerialStatus = switch (type) {
                    case "RETURN" -> "SHIPPED";
                    case "STATUS_CHANGE" -> serialStatusForStock(legs.getFirst().stockStatus());
                    case "TRANSFER" -> serialStatusForStock(leg.stockStatus());
                    default -> "AVAILABLE";
                };
                if (expectedSerialStatus == null || !expectedSerialStatus.equals(leg.serialStatus())) {
                    throw new IllegalArgumentException("Serial is not in a state valid for this movement");
                }
            }
            if (leg.serialId() != null && !"RETURN".equals(type)
                    && (!"TRANSFER".equals(type) || leg.index() == 0)
                    && (!Objects.equals(leg.serialWarehouse(), leg.warehouseId())
                    || !Objects.equals(leg.serialLocation(), leg.locationId()))) {
                throw new IllegalArgumentException("Serial current location does not match posting source");
            }
        }
        if (type.equals("RECEIPT") && legs.stream().anyMatch(l ->
                Set.of("SERIAL", "LOT_AND_SERIAL").contains(l.trackingMethod())
                        && (l.serialNumber() == null || l.serialPublicId() != null))) {
            throw new IllegalArgumentException("Serial receipt requires a new serial number");
        }
        if (legs.stream().anyMatch(l -> l.serialNumber() != null && l.serialPublicId() != null)) {
            throw new IllegalArgumentException("Provide either an existing serial ID or a new serial number, not both");
        }
    }

    private ResolvedLeg registerSerial(long tenantId, ResolvedLeg leg) {
        if (leg.serialNumber() == null) return leg;
        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO inventory_serials
                      (public_id, tenant_id, item_id, lot_id, current_warehouse_id, current_location_id,
                       serial_number, status, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', UTC_TIMESTAMP(6))
                    """, publicId, tenantId, leg.itemId(), leg.lotId(), leg.warehouseId(),
                    leg.locationId(), leg.serialNumber());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Serial number already exists in this tenant");
        }
        Long id = requiredId("SELECT id FROM inventory_serials WHERE tenant_id = ? AND public_id = ?",
                tenantId, publicId, "Serial registration failed");
        return leg.withSerial(id, "AVAILABLE", leg.warehouseId(), leg.locationId());
    }

    private static String balanceKey(ResolvedLeg leg) {
        return leg.warehouseId() + ":" + leg.locationId() + ":" + leg.itemId() + ":"
                + leg.lotId() + ":" + leg.stockStatus();
    }
    private Balance lockOrCreateBalance(long tenantId, ResolvedLeg leg) {
        jdbc.update("""
                INSERT INTO inventory_balances
                  (public_id, tenant_id, warehouse_id, location_id, item_id, lot_id, stock_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, UUID.randomUUID().toString(), tenantId, leg.warehouseId(), leg.locationId(),
                leg.itemId(), leg.lotId(), leg.stockStatus());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, on_hand_qty, reserved_qty
                FROM inventory_balances
                WHERE tenant_id = ? AND warehouse_id = ? AND location_id = ?
                  AND item_id = ? AND lot_key = COALESCE(?, 0) AND stock_status = ?
                FOR UPDATE
                """, tenantId, leg.warehouseId(), leg.locationId(), leg.itemId(), leg.lotId(), leg.stockStatus());
        if (rows.isEmpty()) throw new IllegalStateException("Inventory balance bucket could not be locked");
        Map<String, Object> row = rows.getFirst();
        return new Balance(number(row, "id"), decimal(row.get("on_hand_qty")), decimal(row.get("reserved_qty")));
    }

    private void updateBalance(long id, BigDecimal delta) {
        int changed;
        if (delta.signum() > 0) {
            changed = jdbc.update("""
                    UPDATE inventory_balances
                    SET on_hand_qty = on_hand_qty + ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ?
                    """, delta, id);
        } else {
            changed = jdbc.update("""
                    UPDATE inventory_balances
                    SET on_hand_qty = on_hand_qty - ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND on_hand_qty - reserved_qty >= ?
                    """, delta.abs(), id, delta.abs());
        }
        if (changed != 1) throw new IllegalStateException("Inventory balance changed or available quantity is insufficient");
    }

    private void insertLedger(Access access, NormalizedCommand command, String groupId, int entryNo,
                              ResolvedLeg leg, BigDecimal delta, BigDecimal before, BigDecimal after) {
        jdbc.update("""
                INSERT INTO inventory_ledger
                  (public_id, tenant_id, movement_group_id, request_id, entry_no, movement_type,
                   warehouse_id, location_id, item_id, lot_id, serial_id, stock_status,
                   quantity_delta, balance_before_qty, balance_after_qty, actor_membership_id, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), access.tenantId(), groupId, command.requestId().toString(),
                entryNo, command.movementType(), leg.warehouseId(), leg.locationId(), leg.itemId(),
                leg.lotId(), leg.serialId(), leg.stockStatus(), delta, before, after,
                access.membershipId(), command.notes());
    }

    private void transitionSerial(String movement, List<ResolvedLeg> legs, ResolvedLeg leg) {
        if (leg.serialId() == null) return;
        if ("TRANSFER".equals(movement) && leg.index() == 1) {
            ResolvedLeg destination = legs.get(1);
            jdbc.update("""
                    UPDATE inventory_serials
                    SET current_warehouse_id = ?, current_location_id = ?, version = version + 1,
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ?
                    """, destination.warehouseId(), destination.locationId(), leg.serialId());
        } else if ("ISSUE".equals(movement)) {
            int changed = jdbc.update("""
                    UPDATE inventory_serials
                    SET status = 'SHIPPED', shipped_at = UTC_TIMESTAMP(6),
                        current_warehouse_id = NULL, current_location_id = NULL,
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND status = 'AVAILABLE'
                    """, leg.serialId());
            if (changed != 1) throw new IllegalStateException("Serial shipment transition failed");
        } else if ("RETURN".equals(movement)) {
            int changed = jdbc.update("""
                    UPDATE inventory_serials
                    SET status = 'RETURNED', current_warehouse_id = ?, current_location_id = ?,
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND status = 'SHIPPED'
                    """, leg.warehouseId(), leg.locationId(), leg.serialId());
            if (changed != 1) throw new IllegalStateException("Serial return transition failed");
        } else if ("DAMAGE".equals(movement) && leg.index() == 1) {
            int changed = jdbc.update("""
                    UPDATE inventory_serials SET status = 'DAMAGED', version = version + 1,
                        updated_at = UTC_TIMESTAMP(6) WHERE id = ? AND status = 'AVAILABLE'
                    """, leg.serialId());
            if (changed != 1) throw new IllegalStateException("Serial damage transition failed");
        } else if ("STATUS_CHANGE".equals(movement) && leg.index() == 1) {
            String sourceStatus = serialStatusForStock(legs.getFirst().stockStatus());
            String targetStatus = serialStatusForStock(leg.stockStatus());
            if (sourceStatus == null || targetStatus == null) {
                throw new IllegalArgumentException("Stock status cannot be represented by serial lifecycle state");
            }
            int changed = jdbc.update("""
                    UPDATE inventory_serials SET status = ?, version = version + 1,
                        updated_at = UTC_TIMESTAMP(6) WHERE id = ? AND status = ?
                    """, targetStatus, leg.serialId(), sourceStatus);
            if (changed != 1) throw new IllegalStateException("Serial status transition failed");
        }
    }

    private static String serialStatusForStock(String stockStatus) {
        return switch (stockStatus) {
            case "AVAILABLE" -> "AVAILABLE";
            case "QUARANTINED" -> "QUARANTINED";
            case "DAMAGED" -> "DAMAGED";
            default -> null;
        };
    }
    private void writeAudit(Access access, NormalizedCommand command, String groupId, int count) {
        Map<String, Object> payload = Map.of(
                "movementType", command.movementType(),
                "requestId", command.requestId().toString(),
                "entryCount", count);
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
                """, UUID.randomUUID().toString(), command.requestId().toString(), groupId,
                json, access.membershipId(), access.userPublicId(), access.tenantId());
        if (written != 1) throw new AccessDeniedException("Audit actor is no longer active");
    }

    private Access access(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        WmsMembershipAccess resolved = accessService.resolve(principal.userPublicId(), principal.membershipPublicId());
        if (!Objects.equals(resolved.userPublicId(), principal.userPublicId())
                || !Objects.equals(resolved.tenantId(), principal.tenantId())
                || !Objects.equals(resolved.tenantPublicId(), principal.tenantPublicId())
                || !Objects.equals(resolved.membershipId(), principal.membershipId())
                || !Objects.equals(resolved.membershipPublicId(), principal.membershipPublicId())) {
            throw new AccessDeniedException("Tenant context is unavailable");
        }
        if (!resolved.hasPermission("WMS_INVENTORY_POST")) throw new AccessDeniedException("Inventory posting permission is required");
        return new Access(resolved.tenantId(), resolved.membershipId(), resolved.userPublicId());
    }

    private void lockTenant(long tenantId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE", Long.class, tenantId);
        if (ids.isEmpty()) throw new AccessDeniedException("Active tenant is unavailable");
    }

    private void lockIds(String table, List<Long> ids, long tenantId, String label) {
        ids.stream().filter(Objects::nonNull).distinct().sorted().forEach(id -> {
            List<Long> rows = jdbc.queryForList(
                    "SELECT id FROM " + table + " WHERE id = ? AND tenant_id = ? FOR UPDATE",
                    Long.class, id, tenantId);
            if (rows.isEmpty()) throw new IllegalArgumentException(label + " is no longer available");
        });
    }

    private long requiredId(String sql, Object a, Object b, String message) {
        List<Long> ids = jdbc.queryForList(sql, Long.class, a, b);
        if (ids.isEmpty()) throw new IllegalArgumentException(message);
        return ids.getFirst();
    }

    private long requiredId(String sql, Object a, Object b, Object c, String message) {
        List<Long> ids = jdbc.queryForList(sql, Long.class, a, b, c);
        if (ids.isEmpty()) throw new IllegalArgumentException(message);
        return ids.getFirst();
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new IllegalArgumentException("Referenced inventory record was not found");
        return rows.getFirst();
    }

    private Map<String, Object> requiredRow(String sql, Object a, Object b, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, a, b);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.getFirst();
    }

    private Map<String, Object> requiredRow(String sql, Object a, Object b, Object c, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, a, b, c);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.getFirst();
    }

    private static NormalizedCommand normalize(PostingCommand command) {
        Objects.requireNonNull(command, "Posting command is required");
        if (command.requestId() == null) throw new IllegalArgumentException("Request ID is required");
        String type = command.movementType() == null ? "" : command.movementType().trim().toUpperCase(Locale.ROOT);
        if (!MOVEMENTS.contains(type)) throw new IllegalArgumentException("Movement type is invalid");
        if (command.legs() == null || command.legs().isEmpty() || command.legs().size() > 200) {
            throw new IllegalArgumentException("Posting must contain between 1 and 200 legs");
        }
        List<PostingLeg> legs = command.legs().stream().map(leg -> {
            if (leg == null) throw new IllegalArgumentException("Posting leg is required");
            BigDecimal quantity = leg.quantity();
            if (quantity == null || quantity.signum() <= 0 || quantity.scale() > 4
                    || quantity.precision() > 19) {
                throw new IllegalArgumentException("Quantity must be positive and fit DECIMAL(19,4)");
            }
            quantity = quantity.setScale(4, RoundingMode.UNNECESSARY);
            return new PostingLeg(
                    required(leg.warehousePublicId(), "warehouse public ID"),
                    required(leg.locationPublicId(), "location public ID"),
                    required(leg.itemPublicId(), "item public ID"),
                    optional(leg.lotPublicId()),
                    optional(leg.serialPublicId()),
                    optional(leg.serialNumber()),
                    leg.stockStatus() == null ? "AVAILABLE" : leg.stockStatus().trim().toUpperCase(Locale.ROOT),
                    quantity);
        }).toList();
        String notes = optional(command.notes());
        if (notes != null && notes.length() > 1000) throw new IllegalArgumentException("Notes are too long");
        return new NormalizedCommand(command.requestId(), type, legs, notes);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static BigDecimal signedDelta(String movement, int index, BigDecimal quantity) {
        return switch (movement) {
            case "RECEIPT", "RETURN", "OPENING_BALANCE", "ADJUSTMENT_IN" -> quantity;
            case "ISSUE", "ADJUSTMENT_OUT" -> quantity.negate();
            case "TRANSFER", "DAMAGE", "STATUS_CHANGE" -> index == 0 ? quantity.negate() : quantity;
            default -> throw new IllegalArgumentException("Movement type is unsupported");
        };
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static Long nullableNumber(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private static String string(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }

    public record PostingCommand(UUID requestId, String movementType, List<PostingLeg> legs, String notes) {}
    public record PostingLeg(String warehousePublicId, String locationPublicId, String itemPublicId,
                             String lotPublicId, String serialPublicId, String serialNumber,
                             String stockStatus, BigDecimal quantity) {}

    private record NormalizedCommand(UUID requestId, String movementType, List<PostingLeg> legs, String notes) {}
    private record Access(long tenantId, long membershipId, String userPublicId) {}
    private record Balance(long id, BigDecimal onHand, BigDecimal reserved) {}

    private record ResolvedLeg(int index, String warehousePublicId, String locationPublicId,
                               String itemPublicId, String lotPublicId, String serialPublicId,
                               String serialNumber, String stockStatus, BigDecimal quantity,
                               long warehouseId, Long locationId, Long itemId, Long lotId,
                               Long serialId, String trackingMethod, boolean stockItem, String itemStatus,
                               String warehouseStatus, String locationStatus, String serialStatus,
                               Long serialWarehouse, Long serialLocation) {
        ResolvedLeg(int index, PostingLeg leg, long warehouseId, String warehouseStatus) {
            this(index, leg.warehousePublicId(), leg.locationPublicId(), leg.itemPublicId(),
                    leg.lotPublicId(), leg.serialPublicId(), leg.serialNumber(), leg.stockStatus(),
                    leg.quantity(), warehouseId, null, null, null, null, null, false, null,
                    warehouseStatus, null, null, null, null);
        }
        ResolvedLeg withLocation(long id, String status) {
            return new ResolvedLeg(index, warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                    serialPublicId, serialNumber, stockStatus, quantity, warehouseId, id, itemId, lotId,
                    serialId, trackingMethod, stockItem, itemStatus, warehouseStatus, status,
                    serialStatus, serialWarehouse, serialLocation);
        }
        ResolvedLeg withItem(long id, String tracking, boolean stock, String status) {
            return new ResolvedLeg(index, warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                    serialPublicId, serialNumber, stockStatus, quantity, warehouseId, locationId, id, lotId,
                    serialId, tracking, stock, status, warehouseStatus, locationStatus, serialStatus,
                    serialWarehouse, serialLocation);
        }
        ResolvedLeg withLot(Long id) {
            return new ResolvedLeg(index, warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                    serialPublicId, serialNumber, stockStatus, quantity, warehouseId, locationId, itemId, id,
                    serialId, trackingMethod, stockItem, itemStatus, warehouseStatus, locationStatus,
                    serialStatus, serialWarehouse, serialLocation);
        }
        ResolvedLeg withSerial(Long id, String status, Long warehouse, Long location) {
            return new ResolvedLeg(index, warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                    serialPublicId, serialNumber, stockStatus, quantity, warehouseId, locationId, itemId, lotId,
                    id, trackingMethod, stockItem, itemStatus, warehouseStatus, locationStatus, status,
                    warehouse, location);
        }
    }
}