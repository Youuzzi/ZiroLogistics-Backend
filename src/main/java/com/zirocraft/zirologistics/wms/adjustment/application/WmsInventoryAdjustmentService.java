package com.zirocraft.zirologistics.wms.adjustment.application;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Count-linked inventory adjustment workflow. All stock changes are delegated
 * to WmsInventoryPostingService; this service never writes balances or ledger rows.
 */
@Service
@Profile("wms")
public class WmsInventoryAdjustmentService {
    private final WmsMembershipAccessService accessService;
    private final WmsInventoryPostingService postingService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsInventoryAdjustmentService(WmsMembershipAccessService accessService,
                                         WmsInventoryPostingService postingService,
                                         JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.postingService = Objects.requireNonNull(postingService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(WmsAuthenticatedPrincipal principal) {
        Access access = access(principal, "WMS_INVENTORY_ADJUSTMENT_READ");
        return jdbc.queryForList("""
                SELECT a.public_id, a.document_number, a.adjustment_type, a.status,
                       a.reason_code, a.reason_description, a.requested_at, a.submitted_at,
                       a.approved_at, a.rejected_at, a.posted_at, a.notes, a.version,
                       a.created_at, a.updated_at
                FROM inventory_adjustments a
                WHERE a.tenant_id = ?
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT 200
                """, access.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(WmsAuthenticatedPrincipal principal, String adjustmentPublicId) {
        Access access = access(principal, "WMS_INVENTORY_ADJUSTMENT_READ");
        return view(access.tenantId(), adjustmentPublicId);
    }

    /**
     * Creates one physical-count adjustment from an approved, still-frozen count.
     * A count can be linked to only one adjustment by this locked workflow.
     */
    @Transactional
    public Map<String, Object> createFromApprovedCount(WmsAuthenticatedPrincipal principal,
                                                        String countPublicId,
                                                        CreateFromCountCommand command) {
        if (command == null) throw new IllegalArgumentException("Adjustment command is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_INVENTORY_ADJUSTMENT_CREATE");
        String documentNumber = required(command.documentNumber(), "Document number", 60);
        String notes = optional(command.notes(), 1000);

        Map<String, Object> count = requiredRowForUpdate("""
                SELECT id, public_id, warehouse_id, status, freeze_stock
                FROM stock_counts
                WHERE tenant_id = ? AND public_id = ?
                """, access.tenantId(), required(countPublicId, "Stock count public ID", 36));
        long countId = number(count, "id");
        long warehouseId = number(count, "warehouse_id");
        if (!"APPROVED".equals(string(count, "status")) || !bool(count.get("freeze_stock"))) {
            throw new IllegalStateException("Only an approved variance count that remains frozen can create an adjustment");
        }
        if (requiredLong("""
                SELECT COUNT(*) FROM stock_count_adjustment_links l
                JOIN inventory_adjustments a
                  ON a.id = l.inventory_adjustment_id AND a.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.stock_count_id = ? AND a.status NOT IN ('REJECTED','CANCELLED')
                """, access.tenantId(), countId) > 0) {
            throw new IllegalStateException("The approved stock count already has an inventory adjustment");
        }

        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO inventory_adjustments
                      (public_id, tenant_id, warehouse_id, document_number, adjustment_type, status,
                       reason_code, reason_description, created_by_membership_id, notes)
                    VALUES (?, ?, ?, ?, 'PHYSICAL_COUNT', 'DRAFT', 'PHYSICAL_COUNT',
                            'Generated from an approved physical stock count', ?, ?)
                    """, publicId, access.tenantId(), warehouseId, documentNumber,
                    access.membershipId(), notes);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Inventory adjustment document number already exists", duplicate);
        }
        long adjustmentId = requiredLong(
                "SELECT id FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?",
                access.tenantId(), publicId);

        List<Map<String, Object>> countLines = jdbc.queryForList("""
                SELECT l.id AS count_line_id, l.location_id, l.item_id, l.lot_id, l.stock_status,
                       l.expected_qty, r.id AS result_id, r.counted_qty,
                       i.base_uom_id, i.tracking_method, i.public_id AS item_public_id,
                       wl.public_id AS location_public_id, il.public_id AS lot_public_id
                FROM stock_count_lines l
                JOIN stock_count_scopes s
                  ON s.id = l.stock_count_scope_id AND s.tenant_id = l.tenant_id
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                JOIN warehouse_locations wl
                  ON wl.id = l.location_id AND wl.warehouse_id = l.warehouse_id AND wl.tenant_id = l.tenant_id
                LEFT JOIN inventory_lots il
                  ON il.id = l.lot_id AND il.item_id = l.item_id AND il.tenant_id = l.tenant_id
                JOIN (
                  SELECT tenant_id, stock_count_line_id, MAX(count_round) AS latest_round
                  FROM stock_count_results
                  WHERE result_status = 'SUBMITTED'
                  GROUP BY tenant_id, stock_count_line_id
                ) latest ON latest.tenant_id = l.tenant_id AND latest.stock_count_line_id = l.id
                JOIN stock_count_results r
                  ON r.tenant_id = latest.tenant_id
                 AND r.stock_count_line_id = latest.stock_count_line_id
                 AND r.count_round = latest.latest_round AND r.result_status = 'SUBMITTED'
                WHERE l.tenant_id = ? AND s.stock_count_id = ? AND l.line_status = 'APPROVED'
                ORDER BY l.item_id, l.location_id, l.lot_id, l.stock_status, l.id
                """, access.tenantId(), countId);
        if (countLines.isEmpty()) throw new IllegalStateException("Approved stock count has no final count results");

        Map<Long, Long> adjustmentLineByItem = new LinkedHashMap<>();
        int[] nextLineNumber = {0};
        boolean anyVariance = false;
        for (Map<String, Object> line : countLines) {
            long itemId = number(line, "item_id");
            long adjustmentLineId = adjustmentLineByItem.computeIfAbsent(itemId, ignored -> {
                int lineNumber = ++nextLineNumber[0];
                jdbc.update("""
                        INSERT INTO inventory_adjustment_lines
                          (public_id, tenant_id, inventory_adjustment_id, warehouse_id,
                           line_number, item_id, uom_id, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'COUNTED')
                        """, UUID.randomUUID().toString(), access.tenantId(), adjustmentId,
                        warehouseId, lineNumber, itemId, number(line, "base_uom_id"));
                return requiredLong("SELECT LAST_INSERT_ID()");
            });

            BigDecimal expected = decimal(line.get("expected_qty"));
            BigDecimal counted = decimal(line.get("counted_qty"));
            BigDecimal delta = counted.subtract(expected);
            if (delta.signum() != 0) anyVariance = true;
            long allocationId = insertAllocation(access.tenantId(), adjustmentLineId, itemId, warehouseId,
                    number(line, "location_id"), nullableNumber(line.get("lot_id")),
                    string(line, "stock_status"), expected, counted, delta);

            String tracking = string(line, "tracking_method");
            if (Set.of("SERIAL", "LOT_AND_SERIAL").contains(tracking)) {
                if (!"AVAILABLE".equals(string(line, "stock_status"))) {
                    throw new IllegalStateException("Serialized adjustment for this stock status has no supported serial lifecycle mapping");
                }
                List<Long> expectedSerials = jdbc.queryForList("""
                        SELECT id FROM inventory_serials
                        WHERE tenant_id = ? AND item_id = ? AND lot_id <=> ?
                          AND current_warehouse_id = ? AND current_location_id = ? AND status IN ('AVAILABLE','RESERVED')
                        ORDER BY id FOR UPDATE
                        """, Long.class, access.tenantId(), itemId, nullableNumber(line.get("lot_id")),
                        warehouseId, number(line, "location_id"));
                List<Long> observedSerials = jdbc.queryForList("""
                        SELECT rs.serial_id
                        FROM stock_count_result_serials rs
                        WHERE rs.tenant_id = ? AND rs.stock_count_result_id = ?
                        ORDER BY rs.serial_id
                        """, Long.class, access.tenantId(), number(line, "result_id"));
                if (expected.compareTo(BigDecimal.valueOf(expectedSerials.size())) != 0
                        || counted.compareTo(BigDecimal.valueOf(observedSerials.size())) != 0) {
                    throw new IllegalStateException("Serialized count quantities do not reconcile with exact serial identities");
                }
                Set<Long> expectedSet = Set.copyOf(expectedSerials);
                Set<Long> observedSet = Set.copyOf(observedSerials);
                for (Long serialId : observedSet) {
                    String observation = expectedSet.contains(serialId) ? null : "PRESENT";
                    if (observation != null) insertSerialObservation(access.tenantId(), allocationId, itemId, serialId, observation);
                }
                for (Long serialId : expectedSet) {
                    if (!observedSet.contains(serialId)) {
                        insertSerialObservation(access.tenantId(), allocationId, itemId, serialId, "MISSING");
                    }
                }
                if (!expectedSet.equals(observedSet)) anyVariance = true;
            }
        }
        if (!anyVariance) throw new IllegalStateException("A no-variance stock count must not create an adjustment");

        jdbc.update("""
                INSERT INTO stock_count_adjustment_links
                  (tenant_id, warehouse_id, stock_count_id, inventory_adjustment_id, created_by_membership_id)
                VALUES (?, ?, ?, ?, ?)
                """, access.tenantId(), warehouseId, countId, adjustmentId, access.membershipId());
        audit(access, publicId, "NONE", "DRAFT", "CREATE_FROM_STOCK_COUNT",
                Map.of("stockCountPublicId", countPublicId));
        return view(access.tenantId(), publicId);
    }


    /**
     * Creates a standalone, reason-coded quantity adjustment. This first release
     * intentionally limits manual adjustments to AVAILABLE stock and quantity
     * corrections; DAMAGE/EXPIRY status reclassification remains on the dedicated
     * paired-movement boundary and cannot be disguised as an ADJUSTMENT_IN/OUT.
     */
    @Transactional
    public Map<String, Object> createManual(WmsAuthenticatedPrincipal principal,
                                            ManualAdjustmentCommand command) {
        if (command == null) throw new IllegalArgumentException("Manual adjustment command is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_INVENTORY_ADJUSTMENT_CREATE");
        String documentNumber = required(command.documentNumber(), "Document number", 60);
        String warehousePublicId = required(command.warehousePublicId(), "Warehouse public ID", 36);
        String type = required(command.adjustmentType(), "Adjustment type", 30).toUpperCase();
        if (!Set.of("LOSS", "FOUND", "CORRECTION", "OTHER").contains(type)) {
            throw new IllegalArgumentException("Manual quantity adjustment type must be LOSS, FOUND, CORRECTION or OTHER; PHYSICAL_COUNT is count-linked and DAMAGE/EXPIRY require status reclassification");
        }
        String reasonCode = required(command.reasonCode(), "Reason code", 50).toUpperCase();
        String reasonDescription = optional(command.reasonDescription(), 500);
        String notes = optional(command.notes(), 1000);
        if (command.allocations() == null || command.allocations().isEmpty() || command.allocations().size() > 100) {
            throw new IllegalArgumentException("Manual adjustment must contain between 1 and 100 allocations");
        }

        Map<String, Object> warehouse = requiredRow("""
                SELECT id, status FROM warehouses
                WHERE tenant_id = ? AND public_id = ?
                """, access.tenantId(), warehousePublicId);
        long warehouseId = number(warehouse, "id");
        if (!"ACTIVE".equals(string(warehouse, "status"))) {
            throw new IllegalArgumentException("Warehouse must be active");
        }

        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO inventory_adjustments
                      (public_id, tenant_id, warehouse_id, document_number, adjustment_type, status,
                       reason_code, reason_description, created_by_membership_id, notes)
                    VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)
                    """, publicId, access.tenantId(), warehouseId, documentNumber, type,
                    reasonCode, reasonDescription, access.membershipId(), notes);
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Inventory adjustment document number already exists", duplicate);
        }
        long adjustmentId = requiredLong(
                "SELECT id FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?",
                access.tenantId(), publicId);

        Map<Long, Long> lineByItem = new LinkedHashMap<>();
        int[] nextLineNumber = {0};
        Set<String> buckets = new java.util.HashSet<>();
        boolean anyVariance = false;
        for (ManualAllocationCommand allocationCommand : command.allocations()) {
            if (allocationCommand == null) throw new IllegalArgumentException("Manual adjustment allocation is required");
            String locationPublicId = required(allocationCommand.locationPublicId(), "Location public ID", 36);
            String itemPublicId = required(allocationCommand.itemPublicId(), "Item public ID", 36);
            String stockStatus = allocationCommand.stockStatus() == null
                    ? "AVAILABLE" : allocationCommand.stockStatus().trim().toUpperCase();
            if (!"AVAILABLE".equals(stockStatus)) {
                throw new IllegalArgumentException("Manual quantity adjustments currently support AVAILABLE stock only; use an explicit status-change workflow for other stock statuses");
            }
            BigDecimal counted = normalizeAdjustmentQuantity(allocationCommand.countedQty());
            String lotPublicId = optional(allocationCommand.lotPublicId(), 36);
            String bucketKey = locationPublicId + ":" + itemPublicId + ":" + Objects.toString(lotPublicId, "") + ":" + stockStatus;
            if (!buckets.add(bucketKey)) throw new IllegalArgumentException("Duplicate inventory bucket in manual adjustment");

            Map<String, Object> location = requiredRow("""
                    SELECT id, status FROM warehouse_locations
                    WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ?
                    """, access.tenantId(), warehouseId, locationPublicId);
            if (!"ACTIVE".equals(string(location, "status"))) {
                throw new IllegalArgumentException("Location must be active");
            }
            long locationId = number(location, "id");
            Map<String, Object> item = requiredRow("""
                    SELECT id, base_uom_id, tracking_method, is_stock_item, status
                    FROM items WHERE tenant_id = ? AND public_id = ?
                    """, access.tenantId(), itemPublicId);
            long itemId = number(item, "id");
            if (!"ACTIVE".equals(string(item, "status")) || !bool(item.get("is_stock_item"))) {
                throw new IllegalArgumentException("Only active stock items can be adjusted");
            }
            String tracking = string(item, "tracking_method");
            boolean lotTracked = Set.of("LOT", "LOT_AND_SERIAL").contains(tracking);
            boolean serialTracked = Set.of("SERIAL", "LOT_AND_SERIAL").contains(tracking);
            Long lotId = null;
            if (lotPublicId != null) {
                Map<String, Object> lot = requiredRow("""
                        SELECT id, status FROM inventory_lots
                        WHERE tenant_id = ? AND item_id = ? AND public_id = ?
                        """, access.tenantId(), itemId, lotPublicId);
                if (!"ACTIVE".equals(string(lot, "status"))) throw new IllegalArgumentException("Lot must be active");
                lotId = number(lot, "id");
            }
            if (lotTracked != (lotId != null)) {
                throw new IllegalArgumentException("Lot assignment does not match the item's tracking method");
            }

            List<Map<String, Object>> balanceRows = jdbc.queryForList("""
                    SELECT on_hand_qty, reserved_qty FROM inventory_balances
                    WHERE tenant_id = ? AND warehouse_id = ? AND location_id = ? AND item_id = ?
                      AND lot_key = COALESCE(?, 0) AND stock_status = ?
                    FOR UPDATE
                    """, access.tenantId(), warehouseId, locationId, itemId, lotId, stockStatus);
            BigDecimal systemQty = balanceRows.isEmpty()
                    ? BigDecimal.ZERO.setScale(4) : decimal(balanceRows.getFirst().get("on_hand_qty"));
            BigDecimal reservedQty = balanceRows.isEmpty()
                    ? BigDecimal.ZERO.setScale(4) : decimal(balanceRows.getFirst().get("reserved_qty"));
            BigDecimal delta = counted.subtract(systemQty).setScale(4, RoundingMode.UNNECESSARY);
            if ("LOSS".equals(type) && delta.signum() > 0) {
                throw new IllegalArgumentException("LOSS adjustment cannot increase on-hand quantity");
            }
            if ("FOUND".equals(type) && delta.signum() < 0) {
                throw new IllegalArgumentException("FOUND adjustment cannot decrease on-hand quantity");
            }
            if (delta.signum() < 0 && systemQty.subtract(reservedQty).compareTo(delta.abs()) < 0) {
                throw new IllegalStateException("Manual adjustment cannot consume reserved inventory");
            }

            List<String> observedPublicIds = allocationCommand.serialPublicIds() == null
                    ? List.of() : allocationCommand.serialPublicIds().stream()
                    .map(value -> required(value, "Serial public ID", 36)).distinct().sorted().toList();
            if (allocationCommand.serialPublicIds() != null
                    && observedPublicIds.size() != allocationCommand.serialPublicIds().size()) {
                throw new IllegalArgumentException("Duplicate serial identity in manual adjustment");
            }
            if (!serialTracked && !observedPublicIds.isEmpty()) {
                throw new IllegalArgumentException("Serial identities are only valid for serial-tracked items");
            }
            if (serialTracked && reservedQty.signum() != 0) {
                throw new IllegalStateException("Serial adjustment cannot proceed while inventory is reserved");
            }

            long lineId = lineByItem.computeIfAbsent(itemId, ignored -> {
                int lineNumber = ++nextLineNumber[0];
                jdbc.update("""
                        INSERT INTO inventory_adjustment_lines
                          (public_id, tenant_id, inventory_adjustment_id, warehouse_id,
                           line_number, item_id, uom_id, status, notes)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'COUNTED', ?)
                        """, UUID.randomUUID().toString(), access.tenantId(), adjustmentId,
                        warehouseId, lineNumber, itemId, number(item, "base_uom_id"),
                        optional(allocationCommand.notes(), 1000));
                return requiredLong("SELECT LAST_INSERT_ID()");
            });
            long allocationId = insertAllocation(access.tenantId(), lineId, itemId, warehouseId,
                    locationId, lotId, stockStatus, systemQty, counted, delta);

            if (serialTracked) {
                List<Long> expectedSerials = jdbc.queryForList("""
                        SELECT id FROM inventory_serials
                        WHERE tenant_id = ? AND item_id = ? AND lot_id <=> ?
                          AND current_warehouse_id = ? AND current_location_id = ? AND status = 'AVAILABLE'
                        ORDER BY id FOR UPDATE
                        """, Long.class, access.tenantId(), itemId, lotId, warehouseId, locationId);
                if (systemQty.compareTo(BigDecimal.valueOf(expectedSerials.size())) != 0) {
                    throw new IllegalStateException("Serial master identities do not reconcile with the locked inventory baseline");
                }
                Set<Long> expectedSet = Set.copyOf(expectedSerials);
                Set<Long> observedSet = new java.util.HashSet<>();
                for (String serialPublicId : observedPublicIds) {
                    Map<String, Object> serial = requiredRow("""
                            SELECT id, status, current_warehouse_id, current_location_id
                            FROM inventory_serials
                            WHERE tenant_id = ? AND item_id = ? AND lot_id <=> ? AND public_id = ?
                            FOR UPDATE
                            """, access.tenantId(), itemId, lotId, serialPublicId);
                    long serialId = number(serial, "id");
                    if (expectedSet.contains(serialId)) {
                        observedSet.add(serialId);
                    } else if ("LOST".equals(string(serial, "status"))
                            && serial.get("current_warehouse_id") == null
                            && serial.get("current_location_id") == null) {
                        observedSet.add(serialId);
                        insertSerialObservation(access.tenantId(), allocationId, itemId, serialId, "PRESENT");
                    } else {
                        throw new IllegalArgumentException("Observed serial must be in this bucket or be a known LOST serial");
                    }
                }
                if (counted.compareTo(BigDecimal.valueOf(observedSet.size())) != 0) {
                    throw new IllegalArgumentException("Counted quantity must equal the number of distinct observed serial identities");
                }
                for (Long serialId : expectedSet) {
                    if (!observedSet.contains(serialId)) {
                        insertSerialObservation(access.tenantId(), allocationId, itemId, serialId, "MISSING");
                    }
                }
                if (!expectedSet.equals(observedSet)) anyVariance = true;
            } else if (delta.signum() != 0) {
                anyVariance = true;
            }
            if (serialTracked && delta.signum() != 0
                    && observedSetSizeMismatch(systemQty, counted, observedPublicIds)) {
                throw new IllegalArgumentException("Serial identity variance does not match quantity variance");
            }
        }
        if (!anyVariance) throw new IllegalArgumentException("Manual adjustment contains no stock quantity or serial identity variance");
        audit(access, publicId, "NONE", "DRAFT", "CREATE_MANUAL",
                Map.of("adjustmentType", type, "reasonCode", reasonCode));
        return view(access.tenantId(), publicId);
    }

    private static BigDecimal normalizeAdjustmentQuantity(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("Counted quantity cannot be negative");
        try {
            value = value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException invalidScale) {
            throw new IllegalArgumentException("Counted quantity must fit DECIMAL(19,4)", invalidScale);
        }
        if (value.precision() > 19) throw new IllegalArgumentException("Counted quantity must fit DECIMAL(19,4)");
        return value;
    }

    private static boolean observedSetSizeMismatch(BigDecimal systemQty, BigDecimal countedQty,
                                                   List<String> observedSerialIds) {
        return countedQty.compareTo(BigDecimal.valueOf(observedSerialIds.size())) != 0
                || systemQty.scale() != 4;
    }

    @Transactional
    public Map<String, Object> submit(WmsAuthenticatedPrincipal principal, String adjustmentPublicId) {
        lockTenant(principal);
        Access access = access(principal, "WMS_INVENTORY_ADJUSTMENT_SUBMIT");
        Map<String, Object> header = header(access.tenantId(), adjustmentPublicId, true);
        if (!"DRAFT".equals(string(header, "status"))) {
            throw new IllegalStateException("Only a draft inventory adjustment can be submitted");
        }
        long allocations = requiredLong("""
                SELECT COUNT(*) FROM inventory_adjustment_allocations al
                JOIN inventory_adjustment_lines ln
                  ON ln.id = al.inventory_adjustment_line_id AND ln.tenant_id = al.tenant_id
                WHERE al.tenant_id = ? AND ln.inventory_adjustment_id = ?
                """, access.tenantId(), number(header, "id"));
        if (allocations == 0) throw new IllegalStateException("Inventory adjustment has no allocations");
        int changed = jdbc.update("""
                UPDATE inventory_adjustments
                SET status = 'SUBMITTED', requested_at = UTC_TIMESTAMP(6),
                    submitted_at = UTC_TIMESTAMP(6), version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status = 'DRAFT'
                """, number(header, "id"), access.tenantId());
        if (changed != 1) throw new IllegalStateException("Inventory adjustment state changed; reload and retry");
        audit(access, adjustmentPublicId, "DRAFT", "SUBMITTED", "SUBMIT", null);
        return view(access.tenantId(), adjustmentPublicId);
    }

    @Transactional
    public Map<String, Object> approve(WmsAuthenticatedPrincipal principal, String adjustmentPublicId,
                                       String approvalNotes) {
        lockTenant(principal);
        Access access = access(principal, "WMS_INVENTORY_ADJUSTMENT_APPROVE");
        Map<String, Object> header = header(access.tenantId(), adjustmentPublicId, true);
        if (!"SUBMITTED".equals(string(header, "status"))) {
            throw new IllegalStateException("Only a submitted inventory adjustment can be approved");
        }
        if (number(header, "created_by_membership_id") == access.membershipId()) {
            throw new AccessDeniedException("The adjustment creator cannot approve their own document");
        }
        validateFreshAllocations(access.tenantId(), number(header, "id"));
        String notes = optional(approvalNotes, 1000);
        int changed = jdbc.update("""
                UPDATE inventory_adjustments
                SET status = 'APPROVED', approved_by_membership_id = ?, approved_at = UTC_TIMESTAMP(6),
                    approval_notes = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status = 'SUBMITTED'
                """, access.membershipId(), notes, number(header, "id"), access.tenantId());
        if (changed != 1) throw new IllegalStateException("Inventory adjustment state changed; reload and retry");
        int reviewedLines = jdbc.update("""
                UPDATE inventory_adjustment_lines
                SET status = 'REVIEWED', updated_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND inventory_adjustment_id = ? AND status = 'COUNTED'
                """, access.tenantId(), number(header, "id"));
        if (reviewedLines == 0) throw new IllegalStateException("Inventory adjustment has no countable lines to approve");
        audit(access, adjustmentPublicId, "SUBMITTED", "APPROVED", "APPROVE", null);
        return view(access.tenantId(), adjustmentPublicId);
    }

    @Transactional
    public Map<String, Object> reject(WmsAuthenticatedPrincipal principal, String adjustmentPublicId,
                                      String rejectionReason) {
        lockTenant(principal);
        Access access = access(principal, "WMS_INVENTORY_ADJUSTMENT_REJECT");
        Map<String, Object> header = header(access.tenantId(), adjustmentPublicId, true);
        if (!"SUBMITTED".equals(string(header, "status"))) {
            throw new IllegalStateException("Only a submitted inventory adjustment can be rejected");
        }
        String reason = required(rejectionReason, "Rejection reason", 1000);
        int changed = jdbc.update("""
                UPDATE inventory_adjustments
                SET status = 'REJECTED', rejected_by_membership_id = ?, rejected_at = UTC_TIMESTAMP(6),
                    approval_notes = ?, version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status = 'SUBMITTED'
                """, access.membershipId(), reason, number(header, "id"), access.tenantId());
        if (changed != 1) throw new IllegalStateException("Inventory adjustment state changed; reload and retry");
        jdbc.update("""
                UPDATE inventory_adjustment_lines
                SET status = 'CANCELLED', updated_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND inventory_adjustment_id = ? AND status = 'COUNTED'
                """, access.tenantId(), number(header, "id"));
        audit(access, adjustmentPublicId, "SUBMITTED", "REJECTED", "REJECT", Map.of("reason", reason));
        return view(access.tenantId(), adjustmentPublicId);
    }


    @Transactional
    public Map<String, Object> post(WmsAuthenticatedPrincipal principal, String adjustmentPublicId) {
        lockTenant(principal);
        Access access = access(principal, "WMS_INVENTORY_ADJUSTMENT_POST");
        Map<String, Object> header = header(access.tenantId(), adjustmentPublicId, true);
        if (!"APPROVED".equals(string(header, "status"))) {
            throw new IllegalStateException("Only an approved inventory adjustment can be posted");
        }
        long adjustmentId = number(header, "id");
        List<Map<String, Object>> links = jdbc.queryForList("""
                SELECT c.id AS count_id, c.public_id AS count_public_id, c.status, c.freeze_stock
                FROM stock_count_adjustment_links l
                JOIN stock_counts c ON c.id = l.stock_count_id AND c.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.inventory_adjustment_id = ?
                FOR UPDATE
                """, access.tenantId(), adjustmentId);
        boolean manual = links.isEmpty();
        Map<String, Object> link = manual ? null : links.getFirst();
        if (manual && "PHYSICAL_COUNT".equals(string(header, "adjustment_type"))) {
            throw new IllegalStateException("Physical-count adjustment must retain its approved stock-count link");
        }
        if (!manual && (!"APPROVED".equals(string(link, "status")) || !bool(link.get("freeze_stock")))) {
            throw new IllegalStateException("Linked stock count is no longer approved and frozen");
        }

        List<Map<String, Object>> allocations = jdbc.queryForList("""
                SELECT al.id AS allocation_id, al.inventory_adjustment_line_id, al.item_id,
                       w.public_id AS warehouse_public_id,
                       al.warehouse_id, al.location_id, al.lot_id, al.stock_status,
                       al.system_qty, al.counted_qty, al.adjustment_qty,
                       i.public_id AS item_public_id, wl.public_id AS location_public_id,
                       il.public_id AS lot_public_id, i.tracking_method
                FROM inventory_adjustment_allocations al
                JOIN inventory_adjustment_lines ln
                  ON ln.id = al.inventory_adjustment_line_id AND ln.tenant_id = al.tenant_id
                JOIN items i ON i.id = al.item_id AND i.tenant_id = al.tenant_id
                JOIN warehouses w ON w.id = al.warehouse_id AND w.tenant_id = al.tenant_id
                JOIN warehouse_locations wl
                  ON wl.id = al.location_id AND wl.warehouse_id = al.warehouse_id AND wl.tenant_id = al.tenant_id
                LEFT JOIN inventory_lots il
                  ON il.id = al.lot_id AND il.item_id = al.item_id AND il.tenant_id = al.tenant_id
                WHERE al.tenant_id = ? AND ln.inventory_adjustment_id = ?
                ORDER BY al.warehouse_id, al.location_id, al.item_id, al.lot_id, al.stock_status, al.id
                FOR UPDATE
                """, access.tenantId(), adjustmentId);
        if (allocations.isEmpty()) throw new IllegalStateException("Approved adjustment has no allocations");

        for (Map<String, Object> allocation : allocations) {
            long allocationId = number(allocation, "allocation_id");
            long lineId = number(allocation, "inventory_adjustment_line_id");
            BigDecimal delta = decimal(allocation.get("adjustment_qty"));
            String tracking = string(allocation, "tracking_method");
            if (Set.of("SERIAL", "LOT_AND_SERIAL").contains(tracking)) {
                if (!"AVAILABLE".equals(string(allocation, "stock_status"))) {
                    throw new IllegalStateException("Serialized adjustment stock status is unsupported");
                }
                List<Map<String, Object>> serials = jdbc.queryForList("""
                        SELECT s.public_id, x.observation_status
                        FROM inventory_adjustment_line_serials x
                        JOIN inventory_serials s ON s.id = x.serial_id AND s.tenant_id = x.tenant_id
                        WHERE x.tenant_id = ? AND x.inventory_adjustment_allocation_id = ?
                        ORDER BY x.serial_id
                        FOR UPDATE
                        """, access.tenantId(), allocationId);
                List<WmsInventoryPostingService.PostingLeg> inbound = new ArrayList<>();
                List<WmsInventoryPostingService.PostingLeg> outbound = new ArrayList<>();
                for (Map<String, Object> serial : serials) {
                    WmsInventoryPostingService.PostingLeg leg = postingLeg(allocation,
                            string(serial, "public_id"), BigDecimal.ONE);
                    if ("PRESENT".equals(string(serial, "observation_status"))) inbound.add(leg);
                    else if ("MISSING".equals(string(serial, "observation_status"))) outbound.add(leg);
                    else throw new IllegalStateException("Unknown serial observation status");
                }
                if (inbound.size() - outbound.size() != delta.intValueExact()) {
                    throw new IllegalStateException("Serial identity variance does not match adjustment quantity");
                }
                BigDecimal baseline = decimal(allocation.get("system_qty"));
                String countPublicId = manual ? null : string(link, "count_public_id");
                postLegs(principal, adjustmentPublicId, countPublicId, lineId,
                        "ADJUSTMENT_IN", inbound, baseline, baseline,
                        manual ? "Manual reason-coded adjustment: found serial stock" : "Physical count found serial stock");
                postLegs(principal, adjustmentPublicId, countPublicId, lineId,
                        "ADJUSTMENT_OUT", outbound, baseline, baseline.add(BigDecimal.valueOf(inbound.size())),
                        manual ? "Manual reason-coded adjustment: lost serial stock" : "Physical count missing serial stock");
            } else if (delta.signum() > 0) {
                postLegs(principal, adjustmentPublicId, manual ? null : string(link, "count_public_id"), lineId,
                        "ADJUSTMENT_IN", List.of(postingLeg(allocation, null, delta)),
                        decimal(allocation.get("system_qty")), decimal(allocation.get("system_qty")),
                        "Approved reason-coded positive variance");
            } else if (delta.signum() < 0) {
                postLegs(principal, adjustmentPublicId, manual ? null : string(link, "count_public_id"), lineId,
                        "ADJUSTMENT_OUT", List.of(postingLeg(allocation, null, delta.abs())),
                        decimal(allocation.get("system_qty")), decimal(allocation.get("system_qty")),
                        "Approved reason-coded negative variance");
            }
        }

        int adjustedLines = jdbc.update("""
                UPDATE inventory_adjustment_lines
                SET status = 'ADJUSTED', updated_at = UTC_TIMESTAMP(6)
                WHERE tenant_id = ? AND inventory_adjustment_id = ? AND status = 'REVIEWED'
                """, access.tenantId(), adjustmentId);
        if (adjustedLines == 0) throw new IllegalStateException("Approved adjustment has no reviewed lines to post");
        int changed = jdbc.update("""
                UPDATE inventory_adjustments
                SET status = 'POSTED', posted_by_membership_id = ?, posted_at = UTC_TIMESTAMP(6),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status = 'APPROVED'
                """, access.membershipId(), adjustmentId, access.tenantId());
        if (changed != 1) throw new IllegalStateException("Inventory adjustment state changed during posting");

        if (!manual) {
            jdbc.update("""
                    UPDATE stock_count_scopes
                    SET scope_status = 'COMPLETED', completed_at = UTC_TIMESTAMP(6)
                    WHERE tenant_id = ? AND stock_count_id = ? AND scope_status = 'IN_PROGRESS'
                    """, access.tenantId(), number(link, "count_id"));
            int countChanged = jdbc.update("""
                    UPDATE stock_counts
                    SET status = 'COMPLETED', freeze_stock = 0, completed_at = UTC_TIMESTAMP(6),
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND tenant_id = ? AND status = 'APPROVED' AND freeze_stock = 1
                    """, number(link, "count_id"), access.tenantId());
            if (countChanged != 1) throw new IllegalStateException("Linked stock count could not be completed");
        }
        audit(access, adjustmentPublicId, "APPROVED", "POSTED", "POST",
                manual ? Map.of("workflow", "MANUAL", "adjustmentType", string(header, "adjustment_type"))
                        : Map.of("workflow", "PHYSICAL_COUNT", "stockCountPublicId", string(link, "count_public_id")));
        return view(access.tenantId(), adjustmentPublicId);
    }

    private void postLegs(WmsAuthenticatedPrincipal principal, String adjustmentPublicId,
                          String countPublicId, long lineId, String movement,
                          List<WmsInventoryPostingService.PostingLeg> legs,
                          BigDecimal expectedSystemQty, BigDecimal expectedOnHandQty, String notes) {
        if (legs.isEmpty()) return;
        BigDecimal currentBaseline = expectedOnHandQty;
        for (int offset = 0; offset < legs.size(); offset += 200) {
            List<WmsInventoryPostingService.PostingLeg> batch =
                    legs.subList(offset, Math.min(offset + 200, legs.size()));
            WmsInventoryPostingService.PostingCommand command =
                    new WmsInventoryPostingService.PostingCommand(UUID.randomUUID(), movement, batch, notes);
            if (countPublicId == null) {
                postingService.postApprovedManualAdjustment(principal, adjustmentPublicId,
                        lineId, expectedSystemQty, currentBaseline, command);
            } else {
                postingService.postApprovedAdjustment(principal, adjustmentPublicId, countPublicId,
                        lineId, expectedSystemQty, currentBaseline, command);
            }
            BigDecimal moved = batch.stream().map(WmsInventoryPostingService.PostingLeg::quantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            currentBaseline = "ADJUSTMENT_IN".equals(movement)
                    ? currentBaseline.add(moved) : currentBaseline.subtract(moved);
        }
    }

    private static WmsInventoryPostingService.PostingLeg postingLeg(Map<String, Object> allocation,
                                                                      String serialPublicId, BigDecimal quantity) {
        return new WmsInventoryPostingService.PostingLeg(
                string(allocation, "warehouse_public_id"), string(allocation, "location_public_id"),
                string(allocation, "item_public_id"), string(allocation, "lot_public_id"),
                serialPublicId, null, string(allocation, "stock_status"), quantity);
    }

    private long insertAllocation(long tenantId, long lineId, long itemId, long warehouseId, long locationId,
                                  Long lotId, String stockStatus, BigDecimal expected,
                                  BigDecimal counted, BigDecimal delta) {
        jdbc.update("""
                INSERT INTO inventory_adjustment_allocations
                  (tenant_id, inventory_adjustment_line_id, item_id, warehouse_id, location_id,
                   lot_id, stock_status, system_qty, counted_qty, adjustment_qty)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, lineId, itemId, warehouseId, locationId, lotId,
                stockStatus, expected, counted, delta);
        return requiredLong("SELECT LAST_INSERT_ID()");
    }

    private void insertSerialObservation(long tenantId, long allocationId, long itemId,
                                         long serialId, String observation) {
        jdbc.update("""
                INSERT INTO inventory_adjustment_line_serials
                  (tenant_id, inventory_adjustment_allocation_id, item_id, serial_id, observation_status)
                VALUES (?, ?, ?, ?, ?)
                """, tenantId, allocationId, itemId, serialId, observation);
    }

    private void validateFreshAllocations(long tenantId, long adjustmentId) {
        List<Map<String, Object>> allocations = jdbc.queryForList("""
                SELECT al.warehouse_id, al.location_id, al.item_id, al.lot_id,
                       al.stock_status, al.system_qty
                FROM inventory_adjustment_allocations al
                JOIN inventory_adjustment_lines ln
                  ON ln.id = al.inventory_adjustment_line_id AND ln.tenant_id = al.tenant_id
                WHERE al.tenant_id = ? AND ln.inventory_adjustment_id = ?
                ORDER BY al.warehouse_id, al.location_id, al.item_id, al.lot_id, al.stock_status
                """, tenantId, adjustmentId);
        for (Map<String, Object> allocation : allocations) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT on_hand_qty FROM inventory_balances
                    WHERE tenant_id = ? AND warehouse_id = ? AND location_id = ? AND item_id = ?
                      AND lot_id <=> ? AND stock_status = ?
                    FOR UPDATE
                    """, tenantId, number(allocation, "warehouse_id"), number(allocation, "location_id"),
                    number(allocation, "item_id"), nullableNumber(allocation.get("lot_id")),
                    string(allocation, "stock_status"));
            BigDecimal current = rows.isEmpty() ? BigDecimal.ZERO.setScale(4) : decimal(rows.getFirst().get("on_hand_qty"));
            if (current.compareTo(decimal(allocation.get("system_qty"))) != 0) {
                throw new IllegalStateException("Adjustment baseline is stale; refresh the adjustment before approval or posting");
            }
        }
    }

    private Map<String, Object> view(long tenantId, String publicId) {
        Map<String, Object> header = header(tenantId, publicId, false);
        long adjustmentId = number(header, "id");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publicId", header.get("public_id"));
        for (String key : List.of("document_number", "adjustment_type", "status",
                "reason_code", "reason_description", "requested_at", "submitted_at", "approved_at",
                "rejected_at", "posted_at", "notes", "version", "created_at", "updated_at")) {
            result.put(key, header.get(key));
        }
        result.put("lines", jdbc.queryForList("""
                SELECT ln.public_id, ln.line_number, i.public_id AS item_public_id,
                       i.sku, i.item_name, ln.status, ln.notes
                FROM inventory_adjustment_lines ln
                JOIN items i ON i.id = ln.item_id AND i.tenant_id = ln.tenant_id
                WHERE ln.tenant_id = ? AND ln.inventory_adjustment_id = ?
                ORDER BY ln.line_number
                """, tenantId, adjustmentId));
        result.put("allocations", jdbc.queryForList("""
                SELECT i.public_id AS item_public_id, wl.public_id AS location_public_id,
                       il.public_id AS lot_public_id, al.stock_status, al.system_qty,
                       al.counted_qty, al.adjustment_qty
                FROM inventory_adjustment_allocations al
                JOIN inventory_adjustment_lines ln
                  ON ln.id = al.inventory_adjustment_line_id AND ln.tenant_id = al.tenant_id
                JOIN items i ON i.id = al.item_id AND i.tenant_id = al.tenant_id
                JOIN warehouse_locations wl
                  ON wl.id = al.location_id AND wl.warehouse_id = al.warehouse_id AND wl.tenant_id = al.tenant_id
                LEFT JOIN inventory_lots il
                  ON il.id = al.lot_id AND il.item_id = al.item_id AND il.tenant_id = al.tenant_id
                WHERE al.tenant_id = ? AND ln.inventory_adjustment_id = ?
                ORDER BY al.id
                """, tenantId, adjustmentId));
        return result;
    }

    private Map<String, Object> header(long tenantId, String publicId, boolean forUpdate) {
        return requiredRow("""
                SELECT a.id, a.public_id, a.tenant_id, a.warehouse_id, a.document_number,
                       a.adjustment_type, a.status, a.reason_code, a.reason_description,
                       a.created_by_membership_id, a.approved_by_membership_id,
                       a.rejected_by_membership_id, a.posted_by_membership_id,
                       a.requested_at, a.submitted_at, a.approved_at, a.rejected_at, a.posted_at,
                       a.approval_notes, a.notes, a.version, a.created_at, a.updated_at
                FROM inventory_adjustments a
                WHERE a.tenant_id = ? AND a.public_id = ?
                """ + (forUpdate ? " FOR UPDATE" : ""), tenantId, required(publicId, "Adjustment public ID", 36));
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
        if (!resolved.hasPermission(permission)) throw new AccessDeniedException("Required inventory adjustment permission is missing");
        return new Access(resolved.tenantId(), resolved.membershipId(), resolved.userPublicId());
    }

    private void lockTenant(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                Long.class, principal.tenantId());
        if (ids.isEmpty()) throw new AccessDeniedException("Active tenant is unavailable");
    }

    private void audit(Access access, String subjectPublicId, String oldStatus, String newStatus,
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
            throw new IllegalStateException("Inventory adjustment audit payload could not be serialized", failure);
        }
        int written = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, operation_ref,
                   event_type, subject_public_id, change_payload)
                SELECT ?, t.id, m.id, 'TENANT_MEMBER', ?, 'WMS_INVENTORY_ADJUSTMENT_UPDATED', ?, ?
                FROM tenants t
                JOIN tenant_memberships m ON m.id = ? AND m.tenant_id = t.id AND m.status = 'ACTIVE'
                JOIN users u ON u.id = m.user_id AND u.public_id = ? AND u.status = 'ACTIVE'
                WHERE t.id = ? AND t.status = 'ACTIVE'
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), subjectPublicId, json,
                access.membershipId(), access.userPublicId(), access.tenantId());
        if (written != 1) throw new AccessDeniedException("Audit actor is no longer active");
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new IllegalArgumentException("Inventory adjustment or referenced record not found");
        return rows.getFirst();
    }

    private Map<String, Object> requiredRowForUpdate(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql + " FOR UPDATE", args);
        if (rows.isEmpty()) throw new IllegalArgumentException("Approved stock count or linked record not found");
        return rows.getFirst();
    }

    private long requiredLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        if (value == null) throw new IllegalStateException("Required inventory adjustment record was not created");
        return value;
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
        if (normalized.length() > max) throw new IllegalArgumentException("Text is too long");
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

    public record CreateFromCountCommand(String documentNumber, String notes) {}
    public record ManualAdjustmentCommand(String documentNumber, String warehousePublicId,
                                          String adjustmentType, String reasonCode,
                                          String reasonDescription, String notes,
                                          List<ManualAllocationCommand> allocations) {}
    public record ManualAllocationCommand(String locationPublicId, String itemPublicId,
                                          String lotPublicId, String stockStatus,
                                          BigDecimal countedQty, List<String> serialPublicIds,
                                          String notes) {}
    private record Access(long tenantId, long membershipId, String userPublicId) {}
}