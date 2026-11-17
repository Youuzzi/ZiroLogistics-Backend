package com.zirocraft.zirologistics.wms.putaway.application;

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

@Service
@Profile("wms")
public class WmsPutawayManagementService {
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> STORAGE_LOCATION_TYPES = Set.of("STORAGE", "RACK", "SHELF", "BIN");
    private final WmsMembershipAccessService accessService;
    private final WmsInventoryPostingService postingService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsPutawayManagementService(WmsMembershipAccessService accessService,
                                       WmsInventoryPostingService postingService,
                                       JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.postingService = Objects.requireNonNull(postingService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(WmsAuthenticatedPrincipal principal) {
        Access access = access(principal, "WMS_PUTAWAY_READ");
        return jdbc.queryForList("""
                SELECT t.public_id, t.task_number, t.priority, t.status, t.planned_at,
                       t.started_at, t.completed_at, t.version, t.created_at, t.updated_at,
                       w.public_id AS warehouse_public_id, w.warehouse_code, w.warehouse_name,
                       r.public_id AS receipt_public_id, r.document_number AS receipt_document_number,
                       m.public_id AS assigned_to_membership_public_id
                FROM putaway_tasks t
                JOIN warehouses w ON w.id = t.warehouse_id AND w.tenant_id = t.tenant_id
                JOIN inbound_receipts r ON r.id = t.inbound_receipt_id AND r.tenant_id = t.tenant_id
                LEFT JOIN tenant_memberships m
                  ON m.id = t.assigned_to_membership_id AND m.tenant_id = t.tenant_id
                WHERE t.tenant_id = ?
                ORDER BY t.created_at DESC, t.id DESC
                LIMIT 200
                """, access.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(WmsAuthenticatedPrincipal principal, String taskPublicId) {
        Access access = access(principal, "WMS_PUTAWAY_READ");
        Map<String, Object> task = publicTask(task(access.tenantId(), taskPublicId));
        task.put("lines", jdbc.queryForList("""
                SELECT l.public_id, rl.public_id AS receipt_line_public_id,
                       i.public_id AS item_public_id, i.sku, i.item_name, i.tracking_method,
                       lot.public_id AS lot_public_id, lot.lot_number,
                       src.public_id AS source_location_public_id, src.location_code AS source_location_code,
                       dst.public_id AS destination_location_public_id, dst.location_code AS destination_location_code,
                       l.planned_qty, l.confirmed_qty, l.status, l.notes,
                       (SELECT COUNT(*) FROM putaway_task_line_serials pls
                        WHERE pls.putaway_task_line_id = l.id AND pls.tenant_id = l.tenant_id) AS assigned_serial_count
                FROM putaway_task_lines l
                JOIN inbound_receipt_lines rl ON rl.id = l.inbound_receipt_line_id AND rl.tenant_id = l.tenant_id
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id = l.lot_id AND lot.tenant_id = l.tenant_id
                JOIN warehouse_locations src ON src.id = l.source_location_id AND src.tenant_id = l.tenant_id
                JOIN warehouse_locations dst ON dst.id = l.destination_location_id AND dst.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.putaway_task_id = ?
                ORDER BY l.created_at, l.id
                """, access.tenantId(), number(task, "id")));
        task.remove("id");
        return task;
    }

    @Transactional
    public Map<String, Object> create(WmsAuthenticatedPrincipal principal, CreateTaskCommand command) {
        Objects.requireNonNull(command, "Putaway task request is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_PUTAWAY_CREATE");
        if (command.lines() == null || command.lines().isEmpty() || command.lines().size() > 200) {
            throw new IllegalArgumentException("Putaway task requires between 1 and 200 lines");
        }
        String taskNumber = required(command.taskNumber(), "task number", 60).toUpperCase(Locale.ROOT);
        String priority = enumValue(command.priority() == null ? "NORMAL" : command.priority(),
                PRIORITIES, "priority");
        Map<String, Object> receipt = requiredRow("""
                SELECT r.id, r.warehouse_id, r.receiving_location_id, r.status,
                       w.public_id AS warehouse_public_id
                FROM inbound_receipts r
                JOIN warehouses w ON w.id = r.warehouse_id AND w.tenant_id = r.tenant_id
                WHERE r.tenant_id = ? AND r.public_id = ?
                """, "Inbound receipt not found", access.tenantId(),
                required(command.receiptPublicId(), "receipt public ID"));
        if (!"POSTED".equals(receipt.get("status"))) {
            throw new IllegalStateException("Putaway tasks can only be created from posted receipts");
        }
        long receiptId = number(receipt, "id");
        long warehouseId = number(receipt, "warehouse_id");
        String sourceLocationPublicId = jdbc.queryForObject(
                "SELECT public_id FROM warehouse_locations WHERE id = ? AND tenant_id = ?",
                String.class, number(receipt, "receiving_location_id"), access.tenantId());

        String taskPublicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO putaway_tasks
                      (public_id, tenant_id, warehouse_id, inbound_receipt_id, task_number,
                       priority, status, created_by_membership_id, planned_at, notes)
                    VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                    """, taskPublicId, access.tenantId(), warehouseId, receiptId, taskNumber,
                    priority, access.membershipId(), command.plannedAt(), optional(command.notes(), 1000));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Putaway task number already exists", duplicate);
        }
        long taskId = requiredLong("SELECT id FROM putaway_tasks WHERE tenant_id = ? AND public_id = ?",
                "Created putaway task not found", access.tenantId(), taskPublicId);

        Set<String> serialsInRequest = new HashSet<>();
        for (TaskLineCommand requested : command.lines()) {
            createLine(access, taskId, receiptId, warehouseId, number(receipt, "receiving_location_id"),
                    requested, serialsInRequest);
        }
        Map<String, Object> created = task(access.tenantId(), taskPublicId);
        audit(access, "PUTAWAY_TASK_CREATED", taskPublicId, Map.of(),
                Map.of("taskNumber", taskNumber, "receiptPublicId", command.receiptPublicId(),
                        "lineCount", command.lines().size()));
        return publicTask(created);
    }

    @Transactional
    public Map<String, Object> ready(WmsAuthenticatedPrincipal principal, String taskPublicId) {
        lockTenant(principal);
        Access access = access(principal, "WMS_PUTAWAY_CREATE");
        Map<String, Object> task = task(access.tenantId(), taskPublicId);
        if (!"DRAFT".equals(task.get("status"))) throw new IllegalStateException("Only draft tasks can be made ready");
        int lines = count("SELECT COUNT(*) FROM putaway_task_lines WHERE tenant_id = ? AND putaway_task_id = ?",
                access.tenantId(), number(task, "id"));
        if (lines == 0) throw new IllegalStateException("Putaway task must contain at least one line");
        jdbc.update("""
                UPDATE putaway_tasks SET status = 'READY', version = version + 1,
                    updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status = 'DRAFT'
                """, number(task, "id"), access.tenantId());
        audit(access, "PUTAWAY_TASK_READY", taskPublicId,
                Map.of("status", "DRAFT"), Map.of("status", "READY"));
        return publicTask(task(access.tenantId(), taskPublicId));
    }

    @Transactional
    public Map<String, Object> assign(WmsAuthenticatedPrincipal principal, String taskPublicId,
                                      AssignTaskCommand command) {
        Objects.requireNonNull(command, "Assignment request is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_PUTAWAY_ASSIGN");
        Map<String, Object> task = task(access.tenantId(), taskPublicId);
        String memberPublicId = required(command.membershipPublicId(), "membership public ID");
        long assigneeId = requiredLong("""
                SELECT id FROM tenant_memberships
                WHERE tenant_id = ? AND public_id = ? AND status = 'ACTIVE'
                """, "Active tenant membership not found", access.tenantId(), memberPublicId);
        String oldStatus = String.valueOf(task.get("status"));
        if (!Set.of("READY", "ASSIGNED").contains(oldStatus)) {
            throw new IllegalStateException("Only ready or assigned tasks can be assigned");
        }
        jdbc.update("""
                UPDATE putaway_tasks SET assigned_to_membership_id = ?, status = 'ASSIGNED',
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ?
                """, assigneeId, number(task, "id"), access.tenantId());
        audit(access, "PUTAWAY_TASK_ASSIGNED", taskPublicId,
                Map.of("status", oldStatus), Map.of("status", "ASSIGNED",
                        "assignedToMembershipPublicId", memberPublicId));
        return publicTask(task(access.tenantId(), taskPublicId));
    }

    @Transactional
    public Map<String, Object> start(WmsAuthenticatedPrincipal principal, String taskPublicId) {
        lockTenant(principal);
        Access access = access(principal, "WMS_PUTAWAY_START");
        Map<String, Object> task = task(access.tenantId(), taskPublicId);
        if (!"ASSIGNED".equals(task.get("status"))) throw new IllegalStateException("Only assigned tasks can be started");
        if (number(task, "assigned_to_membership_id") != access.membershipId()) {
            throw new AccessDeniedException("Only the assigned member can start this putaway task");
        }
        jdbc.update("""
                UPDATE putaway_tasks SET status = 'IN_PROGRESS', started_at = UTC_TIMESTAMP(6),
                    version = version + 1, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ? AND status = 'ASSIGNED'
                """, number(task, "id"), access.tenantId());
        audit(access, "PUTAWAY_TASK_STARTED", taskPublicId,
                Map.of("status", "ASSIGNED"), Map.of("status", "IN_PROGRESS"));
        return publicTask(task(access.tenantId(), taskPublicId));
    }

    @Transactional
    public Map<String, Object> confirm(WmsAuthenticatedPrincipal principal, String taskPublicId,
                                       String linePublicId, ConfirmPutawayCommand command) {
        Objects.requireNonNull(command, "Putaway confirmation request is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_PUTAWAY_CONFIRM");
        if (command.requestId() == null) throw new IllegalArgumentException("Request ID is required");
        Map<String, Object> task = task(access.tenantId(), taskPublicId);
        if (!"IN_PROGRESS".equals(task.get("status"))) {
            throw new IllegalStateException("Putaway task is not in progress");
        }
        if (number(task, "assigned_to_membership_id") != access.membershipId()) {
            throw new AccessDeniedException("Only the assigned member can confirm putaway");
        }
        Map<String, Object> line = requiredRow("""
                SELECT l.id, l.public_id, l.putaway_task_id, l.warehouse_id, l.inbound_receipt_id,
                       l.inbound_receipt_line_id, l.item_id, l.lot_id, l.source_location_id,
                       l.destination_location_id, l.planned_qty, l.confirmed_qty, l.status,
                       i.public_id AS item_public_id, i.tracking_method,
                       w.public_id AS warehouse_public_id,
                       src.public_id AS source_location_public_id,
                       dst.public_id AS destination_location_public_id,
                       lot.public_id AS lot_public_id
                FROM putaway_task_lines l
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                JOIN warehouses w ON w.id = l.warehouse_id AND w.tenant_id = l.tenant_id
                JOIN warehouse_locations src ON src.id = l.source_location_id AND src.tenant_id = l.tenant_id
                JOIN warehouse_locations dst ON dst.id = l.destination_location_id AND dst.tenant_id = l.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id = l.lot_id AND lot.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.putaway_task_id = ? AND l.public_id = ?
                """, "Putaway task line not found", access.tenantId(), number(task, "id"), linePublicId);
        if ("COMPLETED".equals(line.get("status")) || "CANCELLED".equals(line.get("status"))) {
            throw new IllegalStateException("Putaway task line is not eligible for confirmation");
        }
        BigDecimal qty = quantity(command.confirmedQty());
        BigDecimal planned = decimal(line, "planned_qty");
        BigDecimal already = decimal(line, "confirmed_qty");
        if (already.add(qty).compareTo(planned) > 0) {
            throw new IllegalArgumentException("Confirmed quantity exceeds the remaining planned quantity");
        }
        Map<String, Object> destination = requiredRow("""
                SELECT id, status, location_type, allow_mixed_skus, allow_mixed_lots
                FROM warehouse_locations
                WHERE tenant_id = ? AND warehouse_id = ? AND id = ?
                """, "Putaway destination is no longer available", access.tenantId(),
                number(line, "warehouse_id"), number(line, "destination_location_id"));
        if (!"ACTIVE".equals(destination.get("status"))
                || !STORAGE_LOCATION_TYPES.contains(string(destination, "location_type"))) {
            throw new IllegalStateException("Putaway destination is no longer an active storage location");
        }
        validateLocationMixing(access.tenantId(), number(line, "warehouse_id"),
                number(line, "destination_location_id"), number(line, "item_id"),
                line.get("lot_id") == null ? null : number(line, "lot_id"), destination);
        String tracking = String.valueOf(line.get("tracking_method"));
        boolean serialTracked = Set.of("SERIAL", "LOT_AND_SERIAL").contains(tracking);
        List<String> serialIds = command.serialPublicIds() == null ? List.of() : command.serialPublicIds();
        if (new HashSet<>(serialIds).size() != serialIds.size()) {
            throw new IllegalArgumentException("Serial references must be unique");
        }
        List<WmsInventoryPostingService.PostingLeg> legs = new ArrayList<>();
        if (serialTracked) {
            if (qty.stripTrailingZeros().scale() > 0 || qty.compareTo(BigDecimal.valueOf(serialIds.size())) != 0
                    || serialIds.isEmpty() || serialIds.size() > 100) {
                throw new IllegalArgumentException("Serial-tracked putaway quantity must equal 1 per selected serial");
            }
            for (String serialPublicId : serialIds) {
                requiredLong("""
                        SELECT s.id FROM putaway_task_line_serials p
                        JOIN inventory_serials s ON s.id = p.serial_id AND s.tenant_id = p.tenant_id
                        WHERE p.tenant_id = ? AND p.putaway_task_line_id = ? AND s.public_id = ?
                          AND s.status = 'AVAILABLE' AND s.current_warehouse_id = ?
                          AND s.current_location_id = ?
                        """, "Serial is not assigned to this line or is no longer at receiving",
                        access.tenantId(), number(line, "id"), serialPublicId,
                        number(line, "warehouse_id"), number(line, "source_location_id"));
                legs.add(transferLeg(line, true, serialPublicId, BigDecimal.ONE));
                legs.add(transferLeg(line, false, serialPublicId, BigDecimal.ONE));
            }
        } else {
            if (!serialIds.isEmpty()) throw new IllegalArgumentException("Serial references are not allowed for this item");
            legs.add(transferLeg(line, true, null, qty));
            legs.add(transferLeg(line, false, null, qty));
        }

        postingService.post(principal, new WmsInventoryPostingService.PostingCommand(
                command.requestId(), "TRANSFER", legs, optional(command.notes(), 1000)));

        String confirmationPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO putaway_confirmations
                  (public_id, tenant_id, putaway_task_id, putaway_task_line_id, warehouse_id,
                   inbound_receipt_id, inbound_receipt_line_id, item_id, source_location_id,
                   destination_location_id, confirmed_qty, confirmed_by_membership_id, request_id, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, confirmationPublicId, access.tenantId(), number(line, "putaway_task_id"),
                number(line, "id"), number(line, "warehouse_id"), number(line, "inbound_receipt_id"),
                number(line, "inbound_receipt_line_id"), number(line, "item_id"),
                number(line, "source_location_id"), number(line, "destination_location_id"),
                qty, access.membershipId(), command.requestId().toString(), optional(command.notes(), 1000));
        BigDecimal newConfirmed = already.add(qty);
        String newLineStatus = newConfirmed.compareTo(planned) == 0 ? "COMPLETED" : "IN_PROGRESS";
        jdbc.update("""
                UPDATE putaway_task_lines SET confirmed_qty = ?, status = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND tenant_id = ?
                """, newConfirmed, newLineStatus, number(line, "id"), access.tenantId());
        int unfinished = count("""
                SELECT COUNT(*) FROM putaway_task_lines
                WHERE tenant_id = ? AND putaway_task_id = ? AND status <> 'COMPLETED'
                """, access.tenantId(), number(task, "id"));
        if (unfinished == 0) {
            jdbc.update("""
                    UPDATE putaway_tasks SET status = 'COMPLETED', completed_at = UTC_TIMESTAMP(6),
                        version = version + 1, updated_at = UTC_TIMESTAMP(6)
                    WHERE id = ? AND tenant_id = ? AND status = 'IN_PROGRESS'
                    """, number(task, "id"), access.tenantId());
            audit(access, "PUTAWAY_TASK_COMPLETED", taskPublicId,
                    Map.of("status", "IN_PROGRESS"), Map.of("status", "COMPLETED"));
        }
        audit(access, "PUTAWAY_CONFIRMED", confirmationPublicId, Map.of(),
                Map.of("taskPublicId", taskPublicId, "linePublicId", linePublicId,
                        "confirmedQty", qty, "requestId", command.requestId().toString()));
        return Map.of("confirmationPublicId", confirmationPublicId,
                "confirmedQty", qty, "lineStatus", newLineStatus,
                "taskStatus", unfinished == 0 ? "COMPLETED" : "IN_PROGRESS",
                "inventoryMovement", postingServiceResultSummary(command.requestId()));
    }

    private Map<String, Object> postingServiceResultSummary(UUID requestId) {
        return Map.of("requestId", requestId.toString(), "movementType", "TRANSFER");
    }

    private WmsInventoryPostingService.PostingLeg transferLeg(
            Map<String, Object> line, boolean source, String serialPublicId, BigDecimal qty) {
        return new WmsInventoryPostingService.PostingLeg(
                nullSafeString(line.get("warehouse_public_id")),
                source ? string(line, "source_location_public_id") : string(line, "destination_location_public_id"),
                string(line, "item_public_id"), nullableString(line.get("lot_public_id")),
                serialPublicId, null, "AVAILABLE", qty);
    }

    private void createLine(Access access, long taskId, long receiptId, long warehouseId,
                            long sourceLocationId,
                            TaskLineCommand requested, Set<String> serialsInRequest) {
        Map<String, Object> receiptLine = requiredRow("""
                SELECT l.id, l.item_id, l.accepted_qty, l.status, i.public_id AS item_public_id,
                       i.tracking_method
                FROM inbound_receipt_lines l
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.inbound_receipt_id = ? AND l.public_id = ?
                """, "Receipt line not found", access.tenantId(), receiptId,
                required(requested.receiptLinePublicId(), "receipt line public ID"));
        BigDecimal planned = quantity(requested.plannedQty());
        String tracking = string(receiptLine, "tracking_method");
        boolean lotTracked = Set.of("LOT", "LOT_AND_SERIAL").contains(tracking);
        boolean serialTracked = Set.of("SERIAL", "LOT_AND_SERIAL").contains(tracking);
        Long lotId = null;
        String lotPublicId = null;
        if (lotTracked) {
            lotPublicId = required(requested.lotPublicId(), "lot public ID");
            Map<String, Object> lot = requiredRow("""
                    SELECT id, status FROM inventory_lots
                    WHERE tenant_id = ? AND item_id = ? AND public_id = ?
                    """, "Lot not found for receipt item", access.tenantId(),
                    number(receiptLine, "item_id"), lotPublicId);
            if (!"ACTIVE".equals(lot.get("status"))) throw new IllegalArgumentException("Lot is not active");
            lotId = number(lot, "id");
        } else if (requested.lotPublicId() != null && !requested.lotPublicId().isBlank()) {
            throw new IllegalArgumentException("Lot reference is not allowed for this item");
        }

        BigDecimal accepted = lotTracked
                ? decimal(requiredRow("""
                    SELECT accepted_qty FROM inbound_receipt_line_lots
                    WHERE tenant_id = ? AND inbound_receipt_line_id = ? AND lot_id = ?
                    """, "Receipt line has no accepted quantity for this lot",
                    access.tenantId(), number(receiptLine, "id"), lotId), "accepted_qty")
                : decimal(receiptLine, "accepted_qty");
        BigDecimal alreadyAllocated = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.planned_qty), 0.0000)
                FROM putaway_task_lines l
                JOIN putaway_tasks t ON t.id = l.putaway_task_id AND t.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND l.inbound_receipt_line_id = ?
                  AND l.lot_key = COALESCE(?, 0) AND t.status <> 'CANCELLED'
                """, BigDecimal.class, access.tenantId(), number(receiptLine, "id"), lotId);
        if (alreadyAllocated.add(planned).compareTo(accepted) > 0) {
            throw new IllegalArgumentException("Planned quantity exceeds the unallocated accepted receipt quantity");
        }

        Map<String, Object> destination = requiredRow("""
                SELECT id, status, location_type, allow_mixed_skus, allow_mixed_lots
                FROM warehouse_locations
                WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ?
                """, "Putaway destination not found", access.tenantId(), warehouseId,
                required(requested.destinationLocationPublicId(), "destination location public ID"));
        if (!"ACTIVE".equals(destination.get("status"))
                || !STORAGE_LOCATION_TYPES.contains(string(destination, "location_type"))
                || number(destination, "id") == sourceLocationId) {
            throw new IllegalArgumentException("Destination must be an active storage location different from receiving");
        }
        validateLocationMixing(access.tenantId(), warehouseId, number(destination, "id"),
                number(receiptLine, "item_id"), lotId, destination);

        List<String> serialPublicIds = requested.serialPublicIds() == null ? List.of() : requested.serialPublicIds();
        List<Long> serialDatabaseIds = new ArrayList<>();
        if (serialTracked) {
            if (planned.stripTrailingZeros().scale() > 0 || planned.compareTo(BigDecimal.valueOf(serialPublicIds.size())) != 0
                    || serialPublicIds.isEmpty()) {
                throw new IllegalArgumentException("Serial-tracked planned quantity must equal the assigned serial count");
            }
            for (String serialPublicId : serialPublicIds) {
                if (!serialsInRequest.add(serialPublicId)) throw new IllegalArgumentException("Serial is assigned more than once in this task");
                Map<String, Object> serial = requiredRow("""
                        SELECT s.id FROM inbound_receipt_line_serials rs
                        JOIN inventory_serials s ON s.id = rs.serial_id AND s.tenant_id = rs.tenant_id
                        WHERE rs.tenant_id = ? AND rs.inbound_receipt_line_id = ? AND s.public_id = ?
                          AND s.current_warehouse_id = ? AND s.current_location_id = ?
                          AND s.status = 'AVAILABLE' AND (s.lot_id <=> ?)
                        """, "Serial is not available on this posted receipt line", access.tenantId(),
                        number(receiptLine, "id"), required(serialPublicId, "serial public ID"),
                        warehouseId, sourceLocationId, lotId);
                serialDatabaseIds.add(number(serial, "id"));
            }
        } else if (!serialPublicIds.isEmpty()) {
            throw new IllegalArgumentException("Serial references are not allowed for this item");
        }

        String linePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO putaway_task_lines
                  (public_id, tenant_id, putaway_task_id, warehouse_id, inbound_receipt_id,
                   inbound_receipt_line_id, item_id, lot_id, source_location_id,
                   destination_location_id, planned_qty, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, linePublicId, access.tenantId(), taskId, warehouseId, receiptId,
                number(receiptLine, "id"), number(receiptLine, "item_id"), lotId,
                sourceLocationId, number(destination, "id"), planned, optional(requested.notes(), 1000));
        long taskLineId = requiredLong("SELECT id FROM putaway_task_lines WHERE tenant_id = ? AND public_id = ?",
                "Created putaway line not found", access.tenantId(), linePublicId);
        for (Long serialId : serialDatabaseIds) {
            jdbc.update("""
                    INSERT INTO putaway_task_line_serials
                      (tenant_id, putaway_task_line_id, inbound_receipt_line_id, item_id, serial_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, access.tenantId(), taskLineId, number(receiptLine, "id"),
                    number(receiptLine, "item_id"), serialId);
        }
    }

    private void validateLocationMixing(long tenantId, long warehouseId, long locationId,
                                        long itemId, Long lotId, Map<String, Object> destination) {
        if (!Boolean.TRUE.equals(bool(destination.get("allow_mixed_skus")))) {
            int otherItems = count("""
                    SELECT COUNT(DISTINCT item_id) FROM inventory_balances
                    WHERE tenant_id = ? AND warehouse_id = ? AND location_id = ?
                      AND on_hand_qty > 0 AND item_id <> ?
                    """, tenantId, warehouseId, locationId, itemId);
            if (otherItems > 0) throw new IllegalArgumentException("Destination does not allow mixed SKUs");
        }
        if (!Boolean.TRUE.equals(bool(destination.get("allow_mixed_lots")))) {
            int otherLots = count("""
                    SELECT COUNT(DISTINCT lot_key) FROM inventory_balances
                    WHERE tenant_id = ? AND warehouse_id = ? AND location_id = ?
                      AND item_id = ? AND on_hand_qty > 0 AND lot_key <> COALESCE(?, 0)
                    """, tenantId, warehouseId, locationId, itemId, lotId);
            if (otherLots > 0) throw new IllegalArgumentException("Destination does not allow mixed lots");
        }
    }

    private Map<String, Object> task(long tenantId, String taskPublicId) {
        return requiredRow("""
                SELECT t.id, t.public_id, t.task_number, t.priority, t.status, t.assigned_to_membership_id,
                       t.planned_at, t.started_at, t.completed_at, t.version, t.created_at, t.updated_at,
                       w.public_id AS warehouse_public_id, w.warehouse_code, w.warehouse_name,
                       r.public_id AS receipt_public_id, r.document_number AS receipt_document_number
                FROM putaway_tasks t
                JOIN warehouses w ON w.id = t.warehouse_id AND w.tenant_id = t.tenant_id
                JOIN inbound_receipts r ON r.id = t.inbound_receipt_id AND r.tenant_id = t.tenant_id
                WHERE t.tenant_id = ? AND t.public_id = ?
                """, "Putaway task not found", tenantId, taskPublicId);
    }

    private Map<String, Object> publicTask(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.remove("id");
        result.remove("assigned_to_membership_id");
        return result;
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
        if (!resolved.hasPermission(permission)) throw new AccessDeniedException("Required putaway permission is missing");
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
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Putaway audit payload could not be serialized", failure);
        }
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

    private Map<String, Object> requiredRow(String sql, String message, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.getFirst();
    }

    private long requiredLong(String sql, String message, Object... args) {
        List<Long> rows = jdbc.queryForList(sql, Long.class, args);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.getFirst();
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number n) return n.longValue();
        throw new IllegalStateException("Expected numeric field: " + key);
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof BigDecimal d) return d;
        throw new IllegalStateException("Expected decimal field: " + key);
    }

    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof String s) return s;
        throw new IllegalStateException("Expected text field: " + key);
    }

    private static String nullableString(Object value) { return value == null ? null : String.valueOf(value); }
    private static String nullSafeString(Object value) { return value == null ? null : String.valueOf(value); }

    private static Boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return null;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }

    private static String required(String value, String label, int max) {
        String result = required(value, label);
        if (result.length() > max) throw new IllegalArgumentException(label + " is too long");
        return result;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) throw new IllegalArgumentException("Optional text exceeds maximum length");
        return result;
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String result = required(value, label).toUpperCase(Locale.ROOT);
        if (!allowed.contains(result)) throw new IllegalArgumentException(label + " is invalid");
        return result;
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Quantity must be positive");
        try { return value.setScale(4, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("Quantity supports at most four decimals", ex); }
    }

    private record Access(long tenantId, long membershipId, String userPublicId) {}
    public record CreateTaskCommand(String receiptPublicId, String taskNumber, String priority,
                                    java.time.LocalDateTime plannedAt, String notes, List<TaskLineCommand> lines) {}
    public record TaskLineCommand(String receiptLinePublicId, String lotPublicId,
                                  String destinationLocationPublicId, BigDecimal plannedQty,
                                  List<String> serialPublicIds, String notes) {}
    public record AssignTaskCommand(String membershipPublicId) {}
    public record ConfirmPutawayCommand(UUID requestId, BigDecimal confirmedQty,
                                        List<String> serialPublicIds, String notes) {}
}
