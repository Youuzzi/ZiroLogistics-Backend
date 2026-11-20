package com.zirocraft.zirologistics.wms.inventory.application;

import com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException;
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
            "RECEIPT", "ISSUE", "TRANSFER", "TRANSFER_DISPATCH", "TRANSFER_RECEIPT",
            "ADJUSTMENT_IN", "ADJUSTMENT_OUT", "RETURN", "DAMAGE", "STATUS_CHANGE", "OPENING_BALANCE");
    private static final Set<String> STOCK_STATUSES =
            Set.of("AVAILABLE", "QUARANTINED", "DAMAGED", "EXPIRED", "BLOCKED");
    private static final Set<String> SERIAL_STATUSES =
            Set.of("AVAILABLE", "RESERVED", "PICKED", "IN_TRANSIT", "SHIPPED", "DAMAGED",
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
        return postInternal(principal, command, false, null);
    }

    /**
     * Narrow posting entry for an approved manual adjustment with no stock-count link.
     * Manual postings never bypass active stock-count freezes.
     */
    @Transactional
    public Map<String, Object> postManualAdjustment(
            WmsAuthenticatedPrincipal principal, String adjustmentPublicId,
            long adjustmentLineId, BigDecimal expectedSystemQty, BigDecimal expectedOnHandQty,
            PostingCommand command) {
        if (command == null || !Set.of("ADJUSTMENT_IN", "ADJUSTMENT_OUT")
                .contains(command.movementType() == null ? "" : command.movementType().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Manual adjustment posting type is required");
        }
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        lockTenant(principal.tenantId());
        Access access = access(principal);
        Map<String, Object> adjustment = requiredRowForUpdate("""
                SELECT a.id AS adjustment_id, a.adjustment_type
                FROM inventory_adjustments a
                WHERE a.tenant_id = ? AND a.public_id = ? AND a.status = 'APPROVED'
                  AND NOT EXISTS (
                    SELECT 1 FROM stock_count_adjustment_links link
                    WHERE link.tenant_id = a.tenant_id AND link.inventory_adjustment_id = a.id
                  )
                """, access.tenantId(), required(adjustmentPublicId, "adjustment public ID"));
        if (!accessService.resolve(principal.userPublicId(), principal.membershipPublicId())
                .hasPermission("WMS_INVENTORY_ADJUSTMENT_POST")) {
            throw new AccessDeniedException("Manual adjustment posting permission is required");
        }
        String adjustmentType = string(adjustment, "adjustment_type");
        if (!Set.of("CORRECTION", "OTHER", "LOSS", "FOUND").contains(adjustmentType)) {
            throw new AccessDeniedException("Adjustment type is not eligible for manual quantity posting");
        }
        if ("LOSS".equals(adjustmentType) && !"ADJUSTMENT_OUT".equalsIgnoreCase(command.movementType())) {
            throw new AccessDeniedException("LOSS adjustments can only post negative inventory movements");
        }
        if ("FOUND".equals(adjustmentType) && !"ADJUSTMENT_IN".equalsIgnoreCase(command.movementType())) {
            throw new AccessDeniedException("FOUND adjustments can only post positive inventory movements");
        }
        List<Long> lines = jdbc.queryForList("""
                SELECT id FROM inventory_adjustment_lines
                WHERE id = ? AND tenant_id = ? AND inventory_adjustment_id = ?
                FOR UPDATE
                """, Long.class, adjustmentLineId, access.tenantId(), number(adjustment, "adjustment_id"));
        if (lines.isEmpty()) throw new AccessDeniedException("Manual adjustment line does not belong to the approved adjustment");
        if (expectedSystemQty == null || expectedOnHandQty == null) {
            throw new IllegalArgumentException("Manual adjustment baseline quantities are required");
        }
        BigDecimal systemBaseline;
        BigDecimal currentBaseline;
        try {
            systemBaseline = expectedSystemQty.setScale(4, RoundingMode.UNNECESSARY);
            currentBaseline = expectedOnHandQty.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalidScale) {
            throw new IllegalArgumentException("Manual adjustment baseline must fit DECIMAL(19,4)", invalidScale);
        }
        AdjustmentPostingContext context = new AdjustmentPostingContext(
                number(adjustment, "adjustment_id"), null, adjustmentLineId, systemBaseline, currentBaseline);
        return postInternal(principal, command, false, context);
    }

    /**
     * Narrow posting entry for an already-approved adjustment linked to its exact
     * approved, still-frozen stock count. Generic callers cannot request a freeze bypass.
     */
    @Transactional
    public Map<String, Object> postApprovedAdjustment(
            WmsAuthenticatedPrincipal principal, String adjustmentPublicId, String stockCountPublicId,
            long adjustmentLineId, BigDecimal expectedSystemQty, BigDecimal expectedOnHandQty,
            PostingCommand command) {
        if (command == null || !Set.of("ADJUSTMENT_IN", "ADJUSTMENT_OUT")
                .contains(command.movementType() == null ? "" : command.movementType().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Adjustment posting type is required");
        }
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        lockTenant(principal.tenantId());
        Access access = access(principal);
        Map<String, Object> context = requiredRowForUpdate("""
                SELECT a.id AS adjustment_id, c.id AS count_id
                FROM inventory_adjustments a
                JOIN stock_count_adjustment_links link
                  ON link.inventory_adjustment_id = a.id AND link.tenant_id = a.tenant_id
                JOIN stock_counts c
                  ON c.id = link.stock_count_id AND c.tenant_id = link.tenant_id
                WHERE a.tenant_id = ? AND a.public_id = ? AND c.public_id = ?
                  AND a.status = 'APPROVED' AND c.status = 'APPROVED' AND c.freeze_stock = 1
                """, access.tenantId(), required(adjustmentPublicId, "adjustment public ID"),
                required(stockCountPublicId, "stock count public ID"));
        List<Long> lines = jdbc.queryForList("""
                SELECT id FROM inventory_adjustment_lines
                WHERE id = ? AND tenant_id = ? AND inventory_adjustment_id = ?
                FOR UPDATE
                """, Long.class, adjustmentLineId, access.tenantId(), number(context, "adjustment_id"));
        if (lines.isEmpty()) throw new AccessDeniedException("Adjustment line does not belong to the approved adjustment");
        if (expectedSystemQty == null || expectedOnHandQty == null) {
            throw new IllegalArgumentException("Adjustment system and current baseline quantities are required");
        }
        BigDecimal systemBaseline;
        BigDecimal currentBaseline;
        try {
            systemBaseline = expectedSystemQty.setScale(4, RoundingMode.UNNECESSARY);
            currentBaseline = expectedOnHandQty.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalidScale) {
            throw new IllegalArgumentException("Adjustment baseline must fit DECIMAL(19,4)", invalidScale);
        }
        AdjustmentPostingContext postingContext = new AdjustmentPostingContext(
                number(context, "adjustment_id"), number(context, "count_id"), adjustmentLineId,
                systemBaseline, currentBaseline);
        return postInternal(principal, command, false, postingContext);
    }

    @Transactional
    public Map<String, Object> postTransferDispatch(WmsAuthenticatedPrincipal principal, PostingCommand command) {
        if (command == null || !"TRANSFER_DISPATCH".equalsIgnoreCase(command.movementType())) {
            throw new IllegalArgumentException("Transfer dispatch posting type is required");
        }
        return postInternal(principal, command, true, null);
    }

    @Transactional
    public Map<String, Object> postTransferReceipt(WmsAuthenticatedPrincipal principal, PostingCommand command) {
        if (command == null || !"TRANSFER_RECEIPT".equalsIgnoreCase(command.movementType())) {
            throw new IllegalArgumentException("Transfer receipt posting type is required");
        }
        return postInternal(principal, command, true, null);
    }

    @Transactional
    public void markTransferSerialLost(WmsAuthenticatedPrincipal principal, String serialPublicId) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        lockTenant(principal.tenantId());
        Access access = access(principal);
        Map<String,Object> serial = requiredRowForUpdate(
                "SELECT id, status FROM inventory_serials WHERE tenant_id = ? AND public_id = ?",
                access.tenantId(), required(serialPublicId, "serial public ID"));
        if (!"IN_TRANSIT".equals(string(serial, "status"))) {
            throw new IllegalStateException("Only an in-transit serial can be reconciled as lost");
        }
        int changed = jdbc.update("""
                UPDATE inventory_serials SET status='LOST', version=version+1, updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='IN_TRANSIT'
                  AND current_warehouse_id IS NULL AND current_location_id IS NULL
                """, number(serial, "id"), access.tenantId());
        if (changed != 1) throw new IllegalStateException("Serial loss reconciliation failed");
    }

    private Map<String, Object> postInternal(WmsAuthenticatedPrincipal principal, PostingCommand command,
                                             boolean transferBoundary, AdjustmentPostingContext adjustmentContext) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        // Lock the tenant before resolving effective permissions so role/membership mutations
        // that share the tenant lock cannot race authorization with this posting transaction.
        lockTenant(principal.tenantId());
        Access access = access(principal);
        NormalizedCommand normalized = normalize(command);
        if (adjustmentContext != null) {
            normalized = normalized.withReference("INVENTORY_ADJUSTMENT",
                    adjustmentContext.adjustmentId(), adjustmentContext.adjustmentLineId());
            validateAdjustmentPostingAllocation(access.tenantId(), adjustmentContext, normalized);
        }
        boolean transferMovement = Set.of("TRANSFER_DISPATCH", "TRANSFER_RECEIPT").contains(normalized.movementType());
        if (transferMovement != transferBoundary) {
            throw new AccessDeniedException("Transfer movements are available only through the transfer workflow");
        }
        boolean adjustmentMovement = Set.of("ADJUSTMENT_IN", "ADJUSTMENT_OUT").contains(normalized.movementType());
        if (adjustmentMovement != (adjustmentContext != null)) {
            throw new AccessDeniedException("Inventory adjustment movements must use the approved adjustment workflow");
        }

        // Resolve and lock reference rows in the shared warehouse -> location -> item -> lot -> serial order.
        List<ResolvedLeg> legs = resolveAndLockReferences(access.tenantId(), normalized);
        validateMovement(normalized, legs);
        assertNoFrozenStockCount(access.tenantId(), legs, adjustmentContext);
        validateLocationWeightCapacity(access.tenantId(), normalized, legs);
        // New serial rows are created before balance buckets to preserve the global lock order.
        List<ResolvedLeg> registeredLegs = new ArrayList<>(legs.size());
        for (ResolvedLeg leg : legs) {
            registeredLegs.add(leg.serialId() == null && leg.serialNumber() != null
                    ? registerSerial(access.tenantId(), leg) : leg);
        }
        legs = List.copyOf(registeredLegs);
        String movementGroupId = UUID.randomUUID().toString();
        List<Map<String, Object>> entries = new ArrayList<>();
        int entryNo = 1;
        Map<String, Balance> lockedBalances = new HashMap<>();
        legs.stream().sorted(Comparator.comparingLong(ResolvedLeg::warehouseId)
                        .thenComparing(ResolvedLeg::locationId)
                        .thenComparing(ResolvedLeg::itemId)
                        .thenComparing(ResolvedLeg::lotId, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ResolvedLeg::stockStatus))
                .forEach(leg -> {
                    String key = balanceKey(leg);
                    if (!lockedBalances.containsKey(key)) {
                        lockedBalances.put(key, lockOrCreateBalance(access.tenantId(), leg));
                    }
                });
        if (adjustmentContext != null) {
            Set<String> checkedBuckets = new HashSet<>();
            for (ResolvedLeg leg : legs) {
                String key = balanceKey(leg);
                if (checkedBuckets.add(key)
                        && lockedBalances.get(key).onHand().compareTo(adjustmentContext.expectedOnHandQty()) != 0) {
                    throw new IllegalStateException("Adjustment baseline is stale; refresh the adjustment before posting");
                }
            }
        }

        Map<String, BigDecimal> runningOnHand = new HashMap<>();

        for (ResolvedLeg leg : legs) {
            String key = balanceKey(leg);
            Balance balance = lockedBalances.get(key);
            BigDecimal delta = signedDelta(normalized.movementType(), leg.index(), leg.quantity());
            BigDecimal before = runningOnHand.getOrDefault(key, balance.onHand());
            BigDecimal after = before.add(delta).setScale(4, RoundingMode.UNNECESSARY);
            if (after.signum() < 0) throw new IllegalStateException("Insufficient on-hand inventory");
            if (delta.signum() < 0 && before.subtract(balance.reserved()).compareTo(delta.abs()) < 0) {
                throw new IllegalStateException("Insufficient available inventory");
            }
            updateBalance(balance.id(), delta);
            runningOnHand.put(key, after);
            try {
                insertLedger(access, normalized, movementGroupId, entryNo++, leg, delta, before, after);
            } catch (DuplicateKeyException duplicateRequest) {
                throw new IllegalStateException("Inventory request ID has already been used", duplicateRequest);
            }
            transitionSerial(normalized.movementType(), legs, leg);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entryNo", entryNo - 1);
            entry.put("itemPublicId", leg.itemPublicId());
            entry.put("warehousePublicId", leg.warehousePublicId());
            entry.put("locationPublicId", leg.locationPublicId());
            entry.put("stockStatus", leg.stockStatus());
            entry.put("quantityDelta", delta);
            entry.put("balanceBeforeQty", before);
            entry.put("balanceAfterQty", after);
            if (leg.lotPublicId() != null) entry.put("lotPublicId", leg.lotPublicId());
            if (leg.serialId() != null) {
                String serialPublicId = leg.serialPublicId();
                if (serialPublicId == null) {
                    serialPublicId = jdbc.queryForObject("SELECT public_id FROM inventory_serials WHERE id = ? AND tenant_id = ?",
                            String.class, leg.serialId(), access.tenantId());
                }
                entry.put("serialPublicId", serialPublicId);
            }
            entries.add(entry);
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

    /**
     * Enforces physical weight capacity for every location receiving a net
     * positive inventory movement. The tenant row is already locked by the
     * posting boundary, so competing inventory postings cannot race this
     * occupancy calculation.
     */
    private void validateLocationWeightCapacity(long tenantId, NormalizedCommand command, List<ResolvedLeg> legs) {
        Map<Long, List<ResolvedLeg>> byLocation = new HashMap<>();
        for (ResolvedLeg leg : legs) {
            byLocation.computeIfAbsent(leg.locationId(), ignored -> new ArrayList<>()).add(leg);
        }
        Map<Long, ItemWeight> itemWeights = new HashMap<>();
        for (Map.Entry<Long, List<ResolvedLeg>> entry : byLocation.entrySet()) {
            long locationId = entry.getKey();
            Map<String, Object> location = requiredRow("""
                    SELECT max_weight_capacity, weight_uom
                    FROM warehouse_locations
                    WHERE tenant_id = ? AND id = ?
                    """, tenantId, locationId);
            Object capacityValue = location.get("max_weight_capacity");
            if (capacityValue == null) continue;

            String capacityUom = string(location, "weight_uom");
            BigDecimal capacityKg = decimal(capacityValue).multiply(weightFactorToKg(capacityUom));
            BigDecimal movementDeltaKg = BigDecimal.ZERO;
            for (ResolvedLeg leg : entry.getValue()) {
                BigDecimal delta = signedDelta(command.movementType(), leg.index(), leg.quantity());
                if (delta.signum() == 0) continue;
                ItemWeight weight = itemWeights.computeIfAbsent(
                        leg.itemId(), itemId -> loadItemWeight(tenantId, itemId));
                if (weight.weightPerBaseUom() == null || weight.uomCode() == null) {
                    if (delta.signum() > 0) {
                        throw new IllegalStateException(
                                "Item weight must be configured before stock can enter a capacity-controlled location");
                    }
                    // Unknown weight on a removal is conservatively treated as zero.
                    continue;
                }
                movementDeltaKg = movementDeltaKg.add(
                        delta.multiply(weight.weightPerBaseUom()).multiply(weightFactorToKg(weight.uomCode())));
            }
            if (movementDeltaKg.signum() <= 0) continue;

            BigDecimal currentWeightKg = currentLocationWeightKg(tenantId, locationId);
            BigDecimal projectedWeightKg = currentWeightKg.add(movementDeltaKg);
            if (projectedWeightKg.compareTo(capacityKg) > 0) {
                throw new IllegalStateException("Location weight capacity would be exceeded");
            }
        }
    }

    private ItemWeight loadItemWeight(long tenantId, long itemId) {
        Map<String, Object> row = requiredRow("""
                SELECT i.weight_per_base_uom, u.uom_code AS weight_uom_code
                FROM items i
                LEFT JOIN units_of_measure u ON u.id = i.weight_uom_id
                WHERE i.tenant_id = ? AND i.id = ?
                """, tenantId, itemId);
        Object weight = row.get("weight_per_base_uom");
        return new ItemWeight(weight == null ? null : decimal(weight), string(row, "weight_uom_code"));
    }

    private BigDecimal currentLocationWeightKg(long tenantId, long locationId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.on_hand_qty, i.weight_per_base_uom, u.uom_code AS weight_uom_code
                FROM inventory_balances b
                JOIN items i ON i.id = b.item_id AND i.tenant_id = b.tenant_id
                LEFT JOIN units_of_measure u ON u.id = i.weight_uom_id
                WHERE b.tenant_id = ? AND b.location_id = ? AND b.on_hand_qty > 0
                """, tenantId, locationId);
        BigDecimal totalKg = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            Object weight = row.get("weight_per_base_uom");
            String uom = string(row, "weight_uom_code");
            if (weight == null || uom == null) {
                throw new IllegalStateException(
                        "All existing stock must have item weight configured before location capacity can be checked");
            }
            totalKg = totalKg.add(decimal(row.get("on_hand_qty"))
                    .multiply(decimal(weight)).multiply(weightFactorToKg(uom)));
        }
        return totalKg;
    }

    private static BigDecimal weightFactorToKg(String uomCode) {
        if (uomCode == null) throw new IllegalStateException("A mass UOM is required for location capacity checks");
        return switch (uomCode.toUpperCase(Locale.ROOT)) {
            case "KG" -> BigDecimal.ONE;
            case "G" -> new BigDecimal("0.001");
            case "MG" -> new BigDecimal("0.000001");
            case "TONNE" -> new BigDecimal("1000");
            default -> throw new IllegalStateException("Unsupported mass UOM for location capacity checks");
        };
    }

    private record ItemWeight(BigDecimal weightPerBaseUom, String uomCode) {}

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
            Map<String, Object> warehouse = requiredRowForUpdate(
                    "SELECT status FROM warehouses WHERE id = ? AND tenant_id = ?", leg.warehouseId(), tenantId);
            return leg.withWarehouseStatus(string(warehouse, "status"));
        }).toList();
        resolved = resolved.stream().map(leg -> {
            Map<String, Object> location = requiredRow(
                    "SELECT id, status FROM warehouse_locations WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ?",
                    tenantId, leg.warehouseId(), leg.locationPublicId(), "Location not found");
            return leg.withLocation(number(location, "id"), string(location, "status"));
        }).toList();
        lockIds("warehouse_locations", resolved.stream().map(ResolvedLeg::locationId).toList(), tenantId, "location");
        resolved = resolved.stream().map(leg -> {
            Map<String, Object> location = requiredRowForUpdate(
                    "SELECT status FROM warehouse_locations WHERE id = ? AND warehouse_id = ? AND tenant_id = ?",
                    leg.locationId(), leg.warehouseId(), tenantId);
            return leg.withLocation(leg.locationId(), string(location, "status"));
        }).toList();
        resolved = resolved.stream().map(leg -> {
            Map<String, Object> item = requiredRow("""
                    SELECT id, tracking_method, is_stock_item, status
                    FROM items WHERE tenant_id = ? AND public_id = ?
                    """, tenantId, leg.itemPublicId(), "Item not found");
            return leg.withItem(number(item, "id"), string(item, "tracking_method"),
                    bool(item.get("is_stock_item")), string(item, "status"));
        }).toList();
        lockIds("items", resolved.stream().map(ResolvedLeg::itemId).toList(), tenantId, "item");
        resolved = resolved.stream().map(leg -> {
            Map<String, Object> item = requiredRowForUpdate(
                    "SELECT tracking_method, is_stock_item, status FROM items WHERE id = ? AND tenant_id = ?",
                    leg.itemId(), tenantId);
            return leg.withItem(leg.itemId(), string(item, "tracking_method"),
                    bool(item.get("is_stock_item")), string(item, "status"));
        }).toList();
        resolved = resolved.stream().map(leg -> {
            Long lotId = null;
            String lotStatus = null;
            if (leg.lotPublicId() != null) {
                Map<String, Object> lot = requiredRow("""
                        SELECT id, status FROM inventory_lots
                        WHERE tenant_id = ? AND item_id = ? AND public_id = ?
                        """, tenantId, leg.itemId(), leg.lotPublicId(), "Lot not found");
                lotId = number(lot, "id");
                lotStatus = string(lot, "status");
            }
            return leg.withLot(lotId, lotStatus);
        }).toList();
        lockIds("inventory_lots", resolved.stream().map(ResolvedLeg::lotId)
                .filter(Objects::nonNull).toList(), tenantId, "lot");
        resolved = resolved.stream().map(leg -> {
            if (leg.lotId() == null) return leg;
            Map<String, Object> lot = requiredRowForUpdate(
                    "SELECT status FROM inventory_lots WHERE id = ? AND tenant_id = ? AND item_id = ?",
                    leg.lotId(), tenantId, leg.itemId());
            return leg.withLotStatus(string(lot, "status"));
        }).toList();
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
        return result.stream().map(leg -> {
            if (leg.serialId() == null) return leg;
            Map<String, Object> serial = requiredRowForUpdate("""
                    SELECT status, current_warehouse_id, current_location_id
                    FROM inventory_serials WHERE id = ? AND tenant_id = ? AND item_id = ?
                      AND lot_id <=> ?
                    """, leg.serialId(), tenantId, leg.itemId(), leg.lotId());
            return leg.withSerial(leg.serialId(), string(serial, "status"),
                    nullableNumber(serial.get("current_warehouse_id")),
                    nullableNumber(serial.get("current_location_id")));
        }).toList();
    }

    private Map<String, Object> requiredRowForUpdate(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql + " FOR UPDATE", args);
        if (rows.isEmpty()) throw new WmsResourceNotFoundException("Referenced inventory record is no longer available");
        return rows.getFirst();
    }


    private void validateAdjustmentPostingAllocation(long tenantId, AdjustmentPostingContext context,
                                                      NormalizedCommand command) {
        if (command.legs().isEmpty()) throw new IllegalArgumentException("Adjustment posting requires allocation legs");
        for (PostingLeg leg : command.legs()) {
            Map<String, Object> allocation = requiredRow("""
                    SELECT al.id, al.system_qty, al.adjustment_qty, al.stock_status, i.tracking_method
                    FROM inventory_adjustment_allocations al
                    JOIN inventory_adjustment_lines ln
                      ON ln.id = al.inventory_adjustment_line_id AND ln.tenant_id = al.tenant_id
                    JOIN warehouses w ON w.id = al.warehouse_id AND w.tenant_id = al.tenant_id
                    JOIN warehouse_locations wl
                      ON wl.id = al.location_id AND wl.warehouse_id = al.warehouse_id AND wl.tenant_id = al.tenant_id
                    JOIN items i ON i.id = al.item_id AND i.tenant_id = al.tenant_id
                    LEFT JOIN inventory_lots lot
                      ON lot.id = al.lot_id AND lot.item_id = al.item_id AND lot.tenant_id = al.tenant_id
                    WHERE al.tenant_id = ? AND ln.id = ? AND ln.inventory_adjustment_id = ?
                      AND w.public_id = ? AND wl.public_id = ? AND i.public_id = ?
                      AND lot.public_id <=> ? AND al.stock_status = ?
                    """, tenantId, context.adjustmentLineId(), context.adjustmentId(),
                    leg.warehousePublicId(), leg.locationPublicId(), leg.itemPublicId(),
                    leg.lotPublicId(), leg.stockStatus());
            if (decimal(allocation.get("system_qty")).compareTo(context.expectedSystemQty()) != 0) {
                throw new AccessDeniedException("Posting baseline does not match the approved adjustment allocation");
            }
            BigDecimal variance = decimal(allocation.get("adjustment_qty"));
            boolean serialTracked = Set.of("SERIAL", "LOT_AND_SERIAL").contains(string(allocation, "tracking_method"));
            if (serialTracked) {
                if (leg.serialPublicId() == null || leg.serialNumber() != null
                        || leg.quantity().compareTo(BigDecimal.ONE) != 0) {
                    throw new AccessDeniedException("Serialized adjustment must use exact existing serial identities");
                }
                String observation = "ADJUSTMENT_IN".equals(command.movementType()) ? "PRESENT" : "MISSING";
                Integer matching = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM inventory_adjustment_line_serials x
                        JOIN inventory_serials s
                          ON s.id = x.serial_id AND s.tenant_id = x.tenant_id
                        WHERE x.tenant_id = ? AND x.inventory_adjustment_allocation_id = ?
                          AND s.public_id = ? AND x.observation_status = ?
                        """, Integer.class, tenantId, number(allocation, "id"), leg.serialPublicId(), observation);
                if (matching == null || matching != 1) {
                    throw new AccessDeniedException("Serial identity is not part of this adjustment allocation");
                }
            } else {
                if (leg.serialPublicId() != null || leg.serialNumber() != null || command.legs().size() != 1) {
                    throw new AccessDeniedException("Quantity adjustment must contain one non-serialized allocation leg");
                }
                boolean directionMatches = "ADJUSTMENT_IN".equals(command.movementType())
                        ? variance.signum() > 0 && leg.quantity().compareTo(variance) == 0
                        : variance.signum() < 0 && leg.quantity().compareTo(variance.abs()) == 0;
                if (!directionMatches) {
                    throw new AccessDeniedException("Posting quantity does not match the approved adjustment allocation");
                }
            }
        }
    }

    /**
     * Every stock posting checks both source and destination locations after
     * acquiring the same tenant, warehouse and location locks used by count start.
     */
    private void assertNoFrozenStockCount(long tenantId, List<ResolvedLeg> legs,
                                          AdjustmentPostingContext adjustmentContext) {
        Set<String> checked = new HashSet<>();
        for (ResolvedLeg leg : legs) {
            String key = leg.warehouseId() + ":" + leg.locationId();
            if (!checked.add(key)) continue;
            List<Map<String, Object>> counts = jdbc.queryForList("""
                    SELECT c.id, c.status, c.freeze_stock
                    FROM stock_count_scopes s
                    JOIN stock_counts c
                      ON c.id = s.stock_count_id AND c.tenant_id = s.tenant_id
                    WHERE c.tenant_id = ? AND c.warehouse_id = ? AND s.location_id = ?
                      AND c.freeze_stock = 1
                      AND c.status IN ('IN_PROGRESS','SUBMITTED','UNDER_REVIEW','APPROVED')
                    ORDER BY c.id
                    """, tenantId, leg.warehouseId(), leg.locationId());
            if (adjustmentContext == null) {
                if (!counts.isEmpty()) {
                    throw new IllegalStateException("Inventory posting is blocked by an active frozen stock count");
                }
                continue;
            }
            if (adjustmentContext.stockCountId() == null) {
                if (!counts.isEmpty()) {
                    throw new IllegalStateException("Manual inventory adjustment is blocked by an active frozen stock count");
                }
                continue;
            }
            if (counts.size() != 1 || number(counts.getFirst(), "id") != adjustmentContext.stockCountId()
                    || !"APPROVED".equals(string(counts.getFirst(), "status"))) {
                throw new IllegalStateException("Adjustment does not match the exact frozen approved stock count");
            }
        }
    }

    private void validateMovement(NormalizedCommand command, List<ResolvedLeg> legs) {
        String type = command.movementType();
        boolean paired = Set.of("TRANSFER", "DAMAGE", "STATUS_CHANGE").contains(type);
        if (paired && legs.size() != 2) throw new IllegalArgumentException(type + " requires exactly two legs");
        if (!paired && legs.isEmpty()) throw new IllegalArgumentException("At least one posting leg is required");
        for (ResolvedLeg leg : legs) {
            if (!"ACTIVE".equals(leg.itemStatus()) || !leg.stockItem()) {
                throw new IllegalArgumentException("Only active stock items can be posted");
            }
            if (!STOCK_STATUSES.contains(leg.stockStatus())) throw new IllegalArgumentException("Stock status is invalid");
            if (!"ACTIVE".equals(leg.warehouseStatus()) || !"ACTIVE".equals(leg.locationStatus())) {
                throw new IllegalArgumentException("Warehouse and location must be active");
            }
            if (leg.lotId() != null && !"ACTIVE".equals(leg.lotStatus())) {
                throw new IllegalArgumentException("Lot is not active");
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

        }
        if (paired) {
            ResolvedLeg source = legs.get(0);
            ResolvedLeg destination = legs.get(1);
            if (!source.itemId().equals(destination.itemId())
                    || !Objects.equals(source.lotId(), destination.lotId())
                    || source.quantity().compareTo(destination.quantity()) != 0
                    || !Objects.equals(source.serialId(), destination.serialId())
                    || !Objects.equals(source.serialNumber(), destination.serialNumber())) {
                throw new IllegalArgumentException("Paired movement legs must match item, lot, serial and quantity");
            }
            if ("TRANSFER".equals(type)) {
                if (source.warehouseId() != destination.warehouseId()) {
                    throw new IllegalArgumentException("Intra-warehouse TRANSFER cannot cross warehouses; use the inter-warehouse transfer workflow");
                }
                if (Objects.equals(source.locationId(), destination.locationId())) {
                    throw new IllegalArgumentException("Transfer must change physical location");
                }
                if (!source.stockStatus().equals(destination.stockStatus())) {
                    throw new IllegalArgumentException("Transfer must preserve stock status");
                }
            }
            if ("DAMAGE".equals(type)) {
                if (!"AVAILABLE".equals(source.stockStatus()) || !"DAMAGED".equals(destination.stockStatus())) {
                    throw new IllegalArgumentException("Damage must move AVAILABLE stock to DAMAGED");
                }
                if (source.warehouseId() != destination.warehouseId()
                        || source.locationId() != destination.locationId()) {
                    throw new IllegalArgumentException("Damage status movement must remain in the same location");
                }
            }
            if ("STATUS_CHANGE".equals(type)) {
                if (source.stockStatus().equals(destination.stockStatus())) {
                    throw new IllegalArgumentException("Status change must change stock status");
                }
                if (source.warehouseId() != destination.warehouseId()
                        || source.locationId() != destination.locationId()) {
                    throw new IllegalArgumentException("Stock status changes must remain in the same location");
                }
                if (source.serialId() != null && serialStatusForStock(destination.stockStatus()) == null) {
                    throw new IllegalArgumentException("Target stock status is not supported for serial-tracked items");
                }
            }
        }
        if ("TRANSFER_DISPATCH".equals(type) && legs.stream().anyMatch(l -> !"AVAILABLE".equals(l.stockStatus()))) {
            throw new IllegalArgumentException("Transfer dispatch can only consume AVAILABLE stock");
        }
        if ("TRANSFER_RECEIPT".equals(type) && legs.stream().anyMatch(l -> !Set.of("AVAILABLE", "DAMAGED").contains(l.stockStatus()))) {
            throw new IllegalArgumentException("Transfer receipt can only receive AVAILABLE or DAMAGED stock");
        }
        if ("ISSUE".equals(type) && legs.stream().anyMatch(l -> !"AVAILABLE".equals(l.stockStatus()))) {
            throw new IllegalArgumentException("Issue can only consume AVAILABLE stock");
        }
        if ("RETURN".equals(type) && legs.stream().anyMatch(l -> !"QUARANTINED".equals(l.stockStatus()))) {
            throw new IllegalArgumentException("Returned stock must enter QUARANTINED status");
        }
        if (Set.of("ADJUSTMENT_IN", "ADJUSTMENT_OUT", "OPENING_BALANCE").contains(type)
                && legs.stream().anyMatch(l -> Set.of("SERIAL", "LOT_AND_SERIAL").contains(l.trackingMethod())
                    && !"AVAILABLE".equals(l.stockStatus()))) {
            throw new IllegalArgumentException("Serial inventory adjustments must use AVAILABLE stock status");
        }
        for (ResolvedLeg leg : legs) {
            if (leg.serialId() != null) {
                boolean validState = switch (type) {
                    case "RETURN" -> "SHIPPED".equals(leg.serialStatus());
                    case "TRANSFER_DISPATCH" -> "AVAILABLE".equals(leg.serialStatus());
                    case "TRANSFER_RECEIPT" -> "IN_TRANSIT".equals(leg.serialStatus());
                    case "ADJUSTMENT_IN", "OPENING_BALANCE" -> "LOST".equals(leg.serialStatus());
                    case "STATUS_CHANGE" -> Objects.equals(
                            serialStatusForStock(legs.getFirst().stockStatus()), leg.serialStatus())
                            || ("QUARANTINED".equals(legs.getFirst().stockStatus())
                                && "RETURNED".equals(leg.serialStatus()));
                    default -> "AVAILABLE".equals(leg.serialStatus());
                };
                if (!validState) throw new IllegalArgumentException("Serial is not in a state valid for this movement");
            }
            boolean restoringLostSerial = leg.serialId() != null
                    && Set.of("ADJUSTMENT_IN", "OPENING_BALANCE").contains(type)
                    && "LOST".equals(leg.serialStatus());
            if (leg.serialId() != null && !Set.of("RETURN", "TRANSFER_RECEIPT").contains(type) && !restoringLostSerial
                    && (!"TRANSFER".equals(type) || leg.index() == 0)
                    && (!Objects.equals(leg.serialWarehouse(), leg.warehouseId())
                    || !Objects.equals(leg.serialLocation(), leg.locationId()))) {
                throw new IllegalArgumentException("Serial current location does not match posting source");
            }
        }
        boolean inboundSerial = Set.of("RECEIPT", "OPENING_BALANCE", "ADJUSTMENT_IN").contains(type);
        if (legs.stream().anyMatch(l -> l.serialNumber() != null && !inboundSerial)) {
            throw new IllegalArgumentException("A new serial number is only valid for inbound stock");
        }
        if (legs.stream().anyMatch(l -> l.serialNumber() != null && l.serialPublicId() != null)) {
            throw new IllegalArgumentException("Provide either an existing serial ID or a new serial number, not both");
        }
        if (inboundSerial && legs.stream().anyMatch(l -> l.serialNumber() != null
                && !"AVAILABLE".equals(l.stockStatus()))) {
            throw new IllegalArgumentException("New serials must enter AVAILABLE stock");
        }
        if ("RECEIPT".equals(type) && legs.stream().anyMatch(l ->
                Set.of("SERIAL", "LOT_AND_SERIAL").contains(l.trackingMethod())
                        && (l.serialNumber() == null || l.serialPublicId() != null))) {
            throw new IllegalArgumentException("Serial receipt requires a new serial number");
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
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                    """, publicId, tenantId, leg.itemId(), leg.lotId(), leg.warehouseId(),
                    leg.locationId(), leg.serialNumber(), serialStatusForStock(leg.stockStatus()));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Serial number already exists in this tenant");
        }
        Long id = requiredId("SELECT id FROM inventory_serials WHERE tenant_id = ? AND public_id = ?",
                tenantId, publicId, "Serial registration failed");
        String serialStatus = serialStatusForStock(leg.stockStatus());
        if (serialStatus == null) {
            throw new IllegalArgumentException("Receipt stock status cannot be represented by serial lifecycle state");
        }
        return leg.withSerial(id, serialStatus, leg.warehouseId(), leg.locationId());
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
        try {
            jdbc.update("""
                    INSERT INTO inventory_ledger
                      (public_id, tenant_id, movement_group_id, request_id, entry_no, movement_type,
                       warehouse_id, location_id, item_id, lot_id, serial_id, stock_status,
                       quantity_delta, balance_before_qty, balance_after_qty, reference_type,
                       reference_id, reference_line_id, actor_membership_id, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), access.tenantId(), groupId, command.requestId().toString(),
                    entryNo, command.movementType(), leg.warehouseId(), leg.locationId(), leg.itemId(),
                    leg.lotId(), leg.serialId(), leg.stockStatus(), delta, before, after,
                    command.referenceType(), command.referenceId(), command.referenceLineId(),
                    access.membershipId(), command.notes());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("The inventory request ID has already been posted");
        }
    }

    private void transitionSerial(String movement, List<ResolvedLeg> legs, ResolvedLeg leg) {
        if (leg.serialId() == null) return;
        if ("TRANSFER_DISPATCH".equals(movement)) {
            int changed = jdbc.update("""
                    UPDATE inventory_serials SET status='IN_TRANSIT', current_warehouse_id=NULL,
                        current_location_id=NULL, version=version+1, updated_at=UTC_TIMESTAMP(6)
                    WHERE id=? AND status='AVAILABLE' AND current_warehouse_id=? AND current_location_id=?
                    """, leg.serialId(), leg.warehouseId(), leg.locationId());
            if (changed != 1) throw new IllegalStateException("Serial dispatch transition failed");
        } else if ("TRANSFER_RECEIPT".equals(movement)) {
            String targetStatus = "DAMAGED".equals(leg.stockStatus()) ? "DAMAGED" : "AVAILABLE";
            int changed = jdbc.update("""
                    UPDATE inventory_serials SET status=?, current_warehouse_id=?, current_location_id=?,
                        version=version+1, updated_at=UTC_TIMESTAMP(6)
                    WHERE id=? AND status='IN_TRANSIT' AND current_warehouse_id IS NULL AND current_location_id IS NULL
                    """, targetStatus, leg.warehouseId(), leg.locationId(), leg.serialId());
            if (changed != 1) throw new IllegalStateException("Serial receipt transition failed");
        } else if ("TRANSFER".equals(movement) && leg.index() == 1) {
            int changed = jdbc.update("""
                    UPDATE inventory_serials
                    SET current_warehouse_id = ?, current_location_id = ?, version = version + 1,
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND status = 'AVAILABLE'
                      AND current_warehouse_id = ? AND current_location_id = ?
                    """, leg.warehouseId(), leg.locationId(), leg.serialId(),
                    legs.getFirst().warehouseId(), legs.getFirst().locationId());
            if (changed != 1) throw new IllegalStateException("Serial transfer transition failed");
        } else if ("ISSUE".equals(movement)) {
            int changed = jdbc.update("""
                    UPDATE inventory_serials
                    SET status = 'SHIPPED', shipped_at = UTC_TIMESTAMP(6),
                        current_warehouse_id = NULL, current_location_id = NULL,
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND status = 'AVAILABLE'
                      AND current_warehouse_id = ? AND current_location_id = ?
                    """, leg.serialId(), leg.warehouseId(), leg.locationId());
            if (changed != 1) throw new IllegalStateException("Serial shipment transition failed");
        } else if ("RETURN".equals(movement)) {
            int changed = jdbc.update("""
                    UPDATE inventory_serials
                    SET status = 'RETURNED', current_warehouse_id = ?, current_location_id = ?,
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND status = 'SHIPPED'
                      AND current_warehouse_id IS NULL AND current_location_id IS NULL
                    """, leg.warehouseId(), leg.locationId(), leg.serialId());
            if (changed != 1) throw new IllegalStateException("Serial return transition failed");
        } else if ("ADJUSTMENT_OUT".equals(movement)) {
            int changed = jdbc.update("""
                    UPDATE inventory_serials
                    SET status = 'LOST', current_warehouse_id = NULL, current_location_id = NULL,
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND status = 'AVAILABLE'
                      AND current_warehouse_id = ? AND current_location_id = ?
                    """, leg.serialId(), leg.warehouseId(), leg.locationId());
            if (changed != 1) throw new IllegalStateException("Serial loss adjustment failed");
        } else if (Set.of("ADJUSTMENT_IN", "OPENING_BALANCE").contains(movement)
                && leg.serialNumber() == null) {
            int changed = jdbc.update("""
                    UPDATE inventory_serials
                    SET status = 'AVAILABLE', current_warehouse_id = ?, current_location_id = ?,
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND status = 'LOST'
                      AND current_warehouse_id IS NULL AND current_location_id IS NULL
                    """, leg.warehouseId(), leg.locationId(), leg.serialId());
            if (changed != 1) throw new IllegalStateException("Serial inventory restoration failed");
        } else if ("DAMAGE".equals(movement) && leg.index() == 1) {
            int changed = jdbc.update("""
                    UPDATE inventory_serials SET status = 'DAMAGED', version = version + 1,
                        updated_at = UTC_TIMESTAMP(6) WHERE id = ? AND status = 'AVAILABLE'
                          AND current_warehouse_id = ? AND current_location_id = ?
                    """, leg.serialId(), legs.getFirst().warehouseId(), legs.getFirst().locationId());
            if (changed != 1) throw new IllegalStateException("Serial damage transition failed");
        } else if ("STATUS_CHANGE".equals(movement) && leg.index() == 1) {
            String sourceStatus = legs.getFirst().serialStatus();
            String targetStatus = serialStatusForStock(leg.stockStatus());
            if (sourceStatus == null || targetStatus == null) {
                throw new IllegalArgumentException("Stock status cannot be represented by serial lifecycle state");
            }
            int changed = jdbc.update("""
                    UPDATE inventory_serials SET status = ?, version = version + 1,
                        updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND status = ?
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
                "movementGroupId", groupId,
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
                """, UUID.randomUUID().toString(), groupId, groupId,
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
            if (rows.isEmpty()) throw new WmsResourceNotFoundException(label + " is no longer available");
        });
    }

    private long requiredId(String sql, Object a, Object b, String message) {
        List<Long> ids = jdbc.queryForList(sql, Long.class, a, b);
        if (ids.isEmpty()) throw new IllegalStateException(message);
        return ids.getFirst();
    }

    private long requiredId(String sql, Object a, Object b, Object c, String message) {
        List<Long> ids = jdbc.queryForList(sql, Long.class, a, b, c);
        if (ids.isEmpty()) throw new WmsResourceNotFoundException(message);
        return ids.getFirst();
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new WmsResourceNotFoundException("Referenced inventory record was not found");
        return rows.getFirst();
    }

    private Map<String, Object> requiredRow(String sql, Object a, Object b, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, a, b);
        if (rows.isEmpty()) throw new WmsResourceNotFoundException(message);
        return rows.getFirst();
    }

    private Map<String, Object> requiredRow(String sql, Object a, Object b, Object c, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, a, b, c);
        if (rows.isEmpty()) throw new WmsResourceNotFoundException(message);
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
            BigDecimal quantity = normalizeQuantity(leg.quantity());
            String serialNumber = optional(leg.serialNumber());
            if (serialNumber != null && serialNumber.length() > 150) {
                throw new IllegalArgumentException("Serial number is too long");
            }
            return new PostingLeg(
                    required(leg.warehousePublicId(), "warehouse public ID"),
                    required(leg.locationPublicId(), "location public ID"),
                    required(leg.itemPublicId(), "item public ID"),
                    optional(leg.lotPublicId()),
                    optional(leg.serialPublicId()),
                    serialNumber,
                    leg.stockStatus() == null ? "AVAILABLE" : leg.stockStatus().trim().toUpperCase(Locale.ROOT),
                    quantity);
        }).toList();
        String notes = optional(command.notes());
        if (notes != null && notes.length() > 1000) throw new IllegalArgumentException("Notes are too long");
        return new NormalizedCommand(command.requestId(), type, legs, notes, null, null, null);
    }

    static BigDecimal normalizeQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Quantity must be positive and fit DECIMAL(19,4)");
        }
        try {
            quantity = quantity.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalidPrecision) {
            throw new IllegalArgumentException("Quantity must be exactly representable at scale 4", invalidPrecision);
        }
        if (quantity.precision() > 19) {
            throw new IllegalArgumentException("Quantity must fit DECIMAL(19,4)");
        }
        return quantity;
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
            case "ISSUE", "ADJUSTMENT_OUT", "TRANSFER_DISPATCH" -> quantity.negate();
            case "TRANSFER_RECEIPT" -> quantity;
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

    private record NormalizedCommand(UUID requestId, String movementType, List<PostingLeg> legs, String notes,
                                     String referenceType, Long referenceId, Long referenceLineId) {
        NormalizedCommand withReference(String type, Long id, Long lineId) {
            return new NormalizedCommand(requestId, movementType, legs, notes, type, id, lineId);
        }
    }
    private record AdjustmentPostingContext(long adjustmentId, Long stockCountId, long adjustmentLineId,
                                            BigDecimal expectedSystemQty, BigDecimal expectedOnHandQty) {}
    private record Access(long tenantId, long membershipId, String userPublicId) {}
    private record Balance(long id, BigDecimal onHand, BigDecimal reserved) {}

    private record ResolvedLeg(int index, String warehousePublicId, String locationPublicId,
                               String itemPublicId, String lotPublicId, String serialPublicId,
                               String serialNumber, String stockStatus, BigDecimal quantity,
                               long warehouseId, Long locationId, Long itemId, Long lotId,
                               Long serialId, String trackingMethod, boolean stockItem, String itemStatus,
                               String warehouseStatus, String locationStatus, String lotStatus, String serialStatus,
                               Long serialWarehouse, Long serialLocation) {
        ResolvedLeg(int index, PostingLeg leg, long warehouseId, String warehouseStatus) {
            this(index, leg.warehousePublicId(), leg.locationPublicId(), leg.itemPublicId(),
                    leg.lotPublicId(), leg.serialPublicId(), leg.serialNumber(), leg.stockStatus(),
                    leg.quantity(), warehouseId, null, null, null, null, null, false, null,
                    warehouseStatus, null, null, null, null, null);
        }
        ResolvedLeg withLocation(long id, String status) {
            return new ResolvedLeg(index, warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                    serialPublicId, serialNumber, stockStatus, quantity, warehouseId, id, itemId, lotId,
                    serialId, trackingMethod, stockItem, itemStatus, warehouseStatus, status,
                    lotStatus, serialStatus, serialWarehouse, serialLocation);
        }
        ResolvedLeg withItem(long id, String tracking, boolean stock, String status) {
            return new ResolvedLeg(index, warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                    serialPublicId, serialNumber, stockStatus, quantity, warehouseId, locationId, id, lotId,
                    serialId, tracking, stock, status, warehouseStatus, locationStatus, lotStatus, serialStatus,
                    serialWarehouse, serialLocation);
        }
        ResolvedLeg withLot(Long id, String status) {
            return new ResolvedLeg(index, warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                    serialPublicId, serialNumber, stockStatus, quantity, warehouseId, locationId, itemId, id,
                    serialId, trackingMethod, stockItem, itemStatus, warehouseStatus, locationStatus,
                    status, serialStatus, serialWarehouse, serialLocation);
        }
        ResolvedLeg withLotStatus(String status) {
            return withLot(lotId, status);
        }
        ResolvedLeg withWarehouseStatus(String status) {
            return new ResolvedLeg(index, warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                    serialPublicId, serialNumber, stockStatus, quantity, warehouseId, locationId, itemId, lotId,
                    serialId, trackingMethod, stockItem, itemStatus, status, locationStatus, lotStatus,
                    serialStatus, serialWarehouse, serialLocation);
        }
        ResolvedLeg withSerial(Long id, String status, Long warehouse, Long location) {
            return new ResolvedLeg(index, warehousePublicId, locationPublicId, itemPublicId, lotPublicId,
                    serialPublicId, serialNumber, stockStatus, quantity, warehouseId, locationId, itemId, lotId,
                    id, trackingMethod, stockItem, itemStatus, warehouseStatus, locationStatus, lotStatus, status,
                    warehouse, location);
        }
    }
}