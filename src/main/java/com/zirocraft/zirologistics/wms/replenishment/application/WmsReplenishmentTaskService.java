package com.zirocraft.zirologistics.wms.replenishment.application;

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
 * Executes same-warehouse replenishment tasks. All stock mutations are delegated
 * to WmsInventoryPostingService; task, confirmation and audit writes join its transaction.
 */
@Service
@Profile("wms")
public class WmsReplenishmentTaskService {
    private final WmsMembershipAccessService accessService;
    private final WmsInventoryPostingService postingService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsReplenishmentTaskService(WmsMembershipAccessService accessService,
                                       WmsInventoryPostingService postingService,
                                       JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.postingService = Objects.requireNonNull(postingService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(WmsAuthenticatedPrincipal principal) {
        Access a = access(principal, "WMS_REPLENISHMENT_READ");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.public_id, t.task_number, t.task_type, t.status, t.priority,
                       t.planned_at, t.released_at, t.started_at, t.completed_at,
                       t.version, w.public_id AS warehouse_public_id, w.warehouse_code
                FROM replenishment_tasks t
                JOIN warehouses w ON w.id=t.warehouse_id AND w.tenant_id=t.tenant_id
                WHERE t.tenant_id=?
                ORDER BY t.created_at DESC, t.id DESC LIMIT 200
                """, a.tenantId());
        return rows;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(WmsAuthenticatedPrincipal principal, String taskPublicId) {
        Access a = access(principal, "WMS_REPLENISHMENT_READ");
        return taskView(a.tenantId(), taskPublicId);
    }

    @Transactional
    public Map<String, Object> release(WmsAuthenticatedPrincipal principal, String taskPublicId,
                                       long expectedVersion) {
        lockTenant(principal);
        Access a = access(principal, "WMS_REPLENISHMENT_TASK_RELEASE");
        Map<String, Object> task = taskHeader(a.tenantId(), taskPublicId, true);
        requireVersion(task, expectedVersion);
        String status = string(task, "status");
        if (!"READY".equals(status) || task.get("released_at") != null) {
            throw new IllegalStateException("Only an unreleased READY replenishment task can be released");
        }
        requireHasLines(a.tenantId(), number(task, "id"));
        int changed = jdbc.update("""
                UPDATE replenishment_tasks SET released_at=UTC_TIMESTAMP(6),
                    version=version+1, updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='READY' AND released_at IS NULL AND version=?
                """, number(task, "id"), a.tenantId(), expectedVersion);
        if (changed != 1) throw new IllegalStateException("Replenishment task changed; reload and retry");
        audit(a, taskPublicId, "RELEASE", Map.of("status", status),
                Map.of("status", status, "released", true));
        return taskView(a.tenantId(), taskPublicId);
    }

    @Transactional
    public Map<String, Object> assign(WmsAuthenticatedPrincipal principal, String taskPublicId,
                                      long expectedVersion, String assigneeMembershipPublicId) {
        lockTenant(principal);
        Access a = access(principal, "WMS_REPLENISHMENT_TASK_ASSIGN");
        Map<String, Object> task = taskHeader(a.tenantId(), taskPublicId, true);
        requireVersion(task, expectedVersion);
        if (!"READY".equals(string(task, "status")) || task.get("released_at") == null) {
            throw new IllegalStateException("Only a released READY task can be assigned");
        }
        Map<String, Object> member = requiredRow("""
                SELECT id FROM tenant_memberships
                WHERE tenant_id=? AND public_id=? AND status='ACTIVE'
                """, a.tenantId(), required(assigneeMembershipPublicId, "Assignee membership public ID"));
        int changed = jdbc.update("""
                UPDATE replenishment_tasks SET assigned_to_membership_id=?, status='ASSIGNED',
                    version=version+1, updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='READY' AND released_at IS NOT NULL AND version=?
                """, number(member, "id"), number(task, "id"), a.tenantId(), expectedVersion);
        if (changed != 1) throw new IllegalStateException("Replenishment task changed; reload and retry");
        audit(a, taskPublicId, "ASSIGN", Map.of("status", "READY"),
                Map.of("status", "ASSIGNED", "assigneeMembershipPublicId", assigneeMembershipPublicId));
        return taskView(a.tenantId(), taskPublicId);
    }

    @Transactional
    public Map<String, Object> start(WmsAuthenticatedPrincipal principal, String taskPublicId,
                                     long expectedVersion) {
        lockTenant(principal);
        Access a = access(principal, "WMS_REPLENISHMENT_TASK_START");
        Map<String, Object> task = taskHeader(a.tenantId(), taskPublicId, true);
        requireVersion(task, expectedVersion);
        if (!"ASSIGNED".equals(string(task, "status"))) {
            throw new IllegalStateException("Only an assigned replenishment task can be started");
        }
        int changed = jdbc.update("""
                UPDATE replenishment_tasks SET status='IN_PROGRESS', started_at=UTC_TIMESTAMP(6),
                    version=version+1, updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status='ASSIGNED' AND version=?
                """, number(task, "id"), a.tenantId(), expectedVersion);
        if (changed != 1) throw new IllegalStateException("Replenishment task changed; reload and retry");
        audit(a, taskPublicId, "START", Map.of("status", "ASSIGNED"),
                Map.of("status", "IN_PROGRESS"));
        return taskView(a.tenantId(), taskPublicId);
    }

    @Transactional
    public Map<String, Object> confirm(WmsAuthenticatedPrincipal principal, String taskPublicId,
                                       ConfirmCommand command) {
        Objects.requireNonNull(command, "Confirmation request is required");
        Objects.requireNonNull(command.requestId(), "Request ID is required");
        lockTenant(principal);
        Access a = access(principal, "WMS_REPLENISHMENT_TASK_CONFIRM");
        Map<String, Object> task = taskHeader(a.tenantId(), taskPublicId, true);
        if (!"IN_PROGRESS".equals(string(task, "status"))) {
            throw new IllegalStateException("Only an in-progress replenishment task can be confirmed");
        }
        Map<String, Object> line = taskLine(a.tenantId(), number(task, "id"), Integer.toString(command.lineNumber()), true);
        BigDecimal quantity = quantity(command.quantity());
        BigDecimal remaining = decimal(line.get("planned_qty")).subtract(decimal(line.get("confirmed_qty")));
        if (quantity.compareTo(remaining) > 0) {
            throw new IllegalArgumentException("Confirmation quantity exceeds the remaining planned quantity");
        }
        String tracking = string(line, "tracking_method");
        boolean serialTracked = Set.of("SERIAL", "LOT_AND_SERIAL").contains(tracking);
        List<String> serials = command.serialPublicIds() == null ? List.of() : command.serialPublicIds();
        if (new HashSet<>(serials).size() != serials.size()) {
            throw new IllegalArgumentException("Duplicate serial identity in confirmation");
        }
        if (serialTracked) {
            if (quantity.stripTrailingZeros().scale() > 0 || quantity.intValueExact() != serials.size()) {
                throw new IllegalArgumentException("Serial identity count must equal the whole-unit confirmation quantity");
            }
        } else if (!serials.isEmpty()) {
            throw new IllegalArgumentException("Non-serial replenishment lines cannot include serial identities");
        }

        // TRANSFER accepts exactly one source/destination pair. Keep each serial
        // movement as its own pair while the enclosing transaction remains atomic.
        if (serialTracked) {
            for (String serialPublicId : serials) {
                String serialId = required(serialPublicId, "Serial public ID");
                UUID serialRequest = UUID.nameUUIDFromBytes((command.requestId() + ":" + serialId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                postingService.post(a.principal(), new WmsInventoryPostingService.PostingCommand(
                        serialRequest, "TRANSFER",
                        List.of(postingLeg(line, serialId, BigDecimal.ONE),
                                destinationPostingLeg(line, serialId, BigDecimal.ONE)),
                        optional(command.notes())));
            }
        } else {
            postingService.post(a.principal(), new WmsInventoryPostingService.PostingCommand(
                    command.requestId(), "TRANSFER",
                    List.of(postingLeg(line, null, quantity), destinationPostingLeg(line, null, quantity)),
                    optional(command.notes())));
        }

        String confirmationPublicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO replenishment_confirmations
                      (public_id, tenant_id, replenishment_task_line_id, replenishment_task_id,
                       warehouse_id, item_id, source_location_id, destination_location_id,
                       confirmation_type, confirmed_qty, reverses_confirmation_id, request_id,
                       confirmed_by_membership_id, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MOVE', ?, NULL, ?, ?, ?)
                    """, confirmationPublicId, a.tenantId(), number(line, "id"), number(task, "id"),
                    number(line, "warehouse_id"), number(line, "item_id"), number(line, "source_location_id"),
                    number(line, "destination_location_id"), quantity, command.requestId().toString(),
                    a.membershipId(), optional(command.notes()));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Confirmation request ID has already been used");
        }
        long confirmationId = requiredLong("""
                SELECT id FROM replenishment_confirmations WHERE tenant_id=? AND public_id=?
                """, a.tenantId(), confirmationPublicId);
        if (serialTracked) {
            for (String serialPublicId : serials) {
                long serialId = requiredLong("""
                        SELECT id FROM inventory_serials WHERE tenant_id=? AND item_id=? AND public_id=?
                        """, a.tenantId(), number(line, "item_id"), serialPublicId);
                jdbc.update("""
                        INSERT INTO replenishment_task_line_serials
                          (tenant_id, replenishment_task_line_id, replenishment_task_id, warehouse_id,
                           item_id, source_location_id, destination_location_id, serial_id, status, moved_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MOVED', UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE status='MOVED', moved_at=UTC_TIMESTAMP(6)
                        """, a.tenantId(), number(line, "id"), number(task, "id"), number(line, "warehouse_id"),
                        number(line, "item_id"), number(line, "source_location_id"),
                        number(line, "destination_location_id"), serialId);
                jdbc.update("""
                        INSERT INTO replenishment_confirmation_serials(tenant_id, confirmation_id, serial_id)
                        VALUES (?, ?, ?)
                        """, a.tenantId(), confirmationId, serialId);
            }
        }
        BigDecimal newConfirmed = decimal(line.get("confirmed_qty")).add(quantity);
        String lineStatus = newConfirmed.compareTo(decimal(line.get("planned_qty"))) == 0
                ? "COMPLETED" : "PARTIALLY_COMPLETED";
        jdbc.update("""
                UPDATE replenishment_task_lines SET confirmed_qty=?, status=?, updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND confirmed_qty=? AND status IN ('PENDING','IN_PROGRESS','PARTIALLY_COMPLETED')
                """, newConfirmed, lineStatus, number(line, "id"), a.tenantId(), decimal(line.get("confirmed_qty")));
        refreshTaskStatus(a.tenantId(), number(task, "id"));
        audit(a, taskPublicId, "CONFIRM", Map.of("linePublicId", command.linePublicId(),
                        "confirmedQty", decimal(line.get("confirmed_qty")).toPlainString()),
                Map.of("confirmationPublicId", confirmationPublicId, "confirmedQty", quantity.toPlainString(),
                        "lineStatus", lineStatus));
        return taskView(a.tenantId(), taskPublicId);
    }

    @Transactional
    public Map<String, Object> reverse(WmsAuthenticatedPrincipal principal, String confirmationPublicId,
                                       ReverseCommand command) {
        Objects.requireNonNull(command, "Reversal request is required");
        Objects.requireNonNull(command.requestId(), "Request ID is required");
        lockTenant(principal);
        Access a = access(principal, "WMS_REPLENISHMENT_CONFIRMATION_REVERSE");
        Map<String, Object> original = requiredRow("""
                SELECT c.*, l.planned_qty, l.confirmed_qty AS line_confirmed_qty,
                       i.tracking_method, i.public_id AS item_public_id,
                       w.public_id AS warehouse_public_id,
                       sl.public_id AS source_location_public_id,
                       dl.public_id AS destination_location_public_id,
                       lot.public_id AS lot_public_id
                FROM replenishment_confirmations c
                JOIN replenishment_task_lines l ON l.id=c.replenishment_task_line_id AND l.tenant_id=c.tenant_id
                JOIN items i ON i.id=c.item_id AND i.tenant_id=c.tenant_id
                JOIN warehouses w ON w.id=c.warehouse_id AND w.tenant_id=c.tenant_id
                JOIN warehouse_locations sl ON sl.id=c.source_location_id AND sl.tenant_id=c.tenant_id
                JOIN warehouse_locations dl ON dl.id=c.destination_location_id AND dl.tenant_id=c.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id=l.lot_id AND lot.tenant_id=l.tenant_id
                WHERE c.tenant_id=? AND c.public_id=? AND c.confirmation_type='MOVE'
                FOR UPDATE
                """, a.tenantId(), required(confirmationPublicId, "Confirmation public ID"));
        long originalId = number(original, "id");
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM replenishment_confirmations
                WHERE tenant_id=? AND reverses_confirmation_id=?
                """, Long.class, a.tenantId(), originalId) > 0) {
            throw new IllegalStateException("This replenishment confirmation has already been reversed");
        }
        if (decimal(original.get("confirmed_qty")).compareTo(decimal(original.get("line_confirmed_qty"))) > 0) {
            throw new IllegalStateException("Confirmation quantity is inconsistent with its task line");
        }
        List<Long> serialIds = jdbc.queryForList("""
                SELECT serial_id FROM replenishment_confirmation_serials
                WHERE tenant_id=? AND confirmation_id=? ORDER BY serial_id
                """, Long.class, a.tenantId(), originalId);
        boolean serialTracked = Set.of("SERIAL", "LOT_AND_SERIAL").contains(string(original, "tracking_method"));
        if (serialTracked && new BigDecimal(serialIds.size()).compareTo(decimal(original.get("confirmed_qty"))) != 0) {
            throw new IllegalStateException("Original serial confirmation evidence is incomplete");
        }
        if (!serialTracked && !serialIds.isEmpty()) {
            throw new IllegalStateException("Non-serial confirmation unexpectedly contains serial evidence");
        }

        if (serialTracked) {
            for (Long serialId : serialIds) {
                String serialPublicId = requiredLongString("""
                        SELECT public_id FROM inventory_serials WHERE tenant_id=? AND id=?
                        """, a.tenantId(), serialId);
                UUID serialRequest = UUID.nameUUIDFromBytes((command.requestId() + ":reverse:" + serialId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                postingService.post(a.principal(), new WmsInventoryPostingService.PostingCommand(
                        serialRequest, "TRANSFER",
                        List.of(reverseDestinationLeg(original, serialPublicId),
                                reverseSourceLeg(original, serialPublicId)), optional(command.notes())));
            }
        } else {
            BigDecimal qty = decimal(original.get("confirmed_qty"));
            postingService.post(a.principal(), new WmsInventoryPostingService.PostingCommand(
                    command.requestId(), "TRANSFER",
                    List.of(reverseDestinationLeg(original, null, qty),
                            reverseSourceLeg(original, null, qty)), optional(command.notes())));
        }
        String reversalPublicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO replenishment_confirmations
                      (public_id, tenant_id, replenishment_task_line_id, replenishment_task_id,
                       warehouse_id, item_id, source_location_id, destination_location_id,
                       confirmation_type, confirmed_qty, reverses_confirmation_id, request_id,
                       confirmed_by_membership_id, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'REVERSAL', ?, ?, ?, ?, ?)
                    """, reversalPublicId, a.tenantId(), number(original, "replenishment_task_line_id"),
                    number(original, "replenishment_task_id"), number(original, "warehouse_id"),
                    number(original, "item_id"), number(original, "source_location_id"),
                    number(original, "destination_location_id"), original.get("confirmed_qty"), originalId,
                    command.requestId().toString(), a.membershipId(), optional(command.notes()));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("Confirmation reversal or request ID already exists");
        }
        long reversalId = requiredLong("""
                SELECT id FROM replenishment_confirmations WHERE tenant_id=? AND public_id=?
                """, a.tenantId(), reversalPublicId);
        for (Long serialId : serialIds) {
            jdbc.update("""
                    INSERT INTO replenishment_confirmation_serials(tenant_id, confirmation_id, serial_id)
                    VALUES (?, ?, ?)
                    """, a.tenantId(), reversalId, serialId);
        }
        BigDecimal adjusted = decimal(original.get("line_confirmed_qty"))
                .subtract(decimal(original.get("confirmed_qty")));
        if (adjusted.signum() < 0) throw new IllegalStateException("Reversal would make confirmed quantity negative");
        String status = adjusted.signum() == 0 ? "PENDING" :
                adjusted.compareTo(decimal(original.get("planned_qty"))) == 0 ? "COMPLETED" : "PARTIALLY_COMPLETED";
        jdbc.update("""
                UPDATE replenishment_task_lines SET confirmed_qty=?, status=?, updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND confirmed_qty=?
                """, adjusted, status, number(original, "replenishment_task_line_id"),
                a.tenantId(), decimal(original.get("line_confirmed_qty")));
        jdbc.update("""
                UPDATE replenishment_task_line_serials SET status='CANCELLED'
                WHERE tenant_id=? AND replenishment_task_line_id=? AND serial_id IN (
                    SELECT serial_id FROM replenishment_confirmation_serials
                    WHERE tenant_id=? AND confirmation_id=?
                )
                """, a.tenantId(), number(original, "replenishment_task_line_id"), a.tenantId(), originalId);
        long taskId = number(original, "replenishment_task_id");
        refreshTaskStatus(a.tenantId(), taskId);
        String taskPublicId = requiredLongString("""
                SELECT public_id FROM replenishment_tasks WHERE tenant_id=? AND id=?
                """, a.tenantId(), taskId);
        audit(a, taskPublicId, "REVERSE_CONFIRMATION",
                Map.of("confirmationPublicId", confirmationPublicId,
                        "confirmedQty", decimal(original.get("confirmed_qty")).toPlainString()),
                Map.of("reversalPublicId", reversalPublicId, "confirmedQty", adjusted.toPlainString()));
        return taskView(a.tenantId(), taskPublicId);
    }

    @Transactional
    public Map<String, Object> cancel(WmsAuthenticatedPrincipal principal, String taskPublicId,
                                      long expectedVersion, String reason) {
        lockTenant(principal);
        Access a = access(principal, "WMS_REPLENISHMENT_TASK_CANCEL");
        Map<String, Object> task = taskHeader(a.tenantId(), taskPublicId, true);
        requireVersion(task, expectedVersion);
        if (Set.of("COMPLETED", "CANCELLED").contains(string(task, "status"))) {
            throw new IllegalStateException("Completed or cancelled task cannot be cancelled");
        }
        String notes = required(reason, "Cancellation reason");
        jdbc.update("""
                UPDATE replenishment_task_lines SET status='CANCELLED', updated_at=UTC_TIMESTAMP(6)
                WHERE tenant_id=? AND replenishment_task_id=? AND status IN ('PENDING','IN_PROGRESS','PARTIALLY_COMPLETED')
                """, a.tenantId(), number(task, "id"));
        jdbc.update("""
                UPDATE replenishment_task_line_serials SET status='CANCELLED'
                WHERE tenant_id=? AND replenishment_task_id=? AND status='ALLOCATED'
                """, a.tenantId(), number(task, "id"));
        int changed = jdbc.update("""
                UPDATE replenishment_tasks SET status='CANCELLED', cancelled_at=UTC_TIMESTAMP(6),
                    notes=CONCAT(COALESCE(notes,''), ?), version=version+1, updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND status NOT IN ('COMPLETED','CANCELLED') AND version=?
                """, "\nCancellation: " + notes, number(task, "id"), a.tenantId(), expectedVersion);
        if (changed != 1) throw new IllegalStateException("Replenishment task changed; reload and retry");
        audit(a, taskPublicId, "CANCEL", Map.of("status", string(task, "status")),
                Map.of("status", "CANCELLED", "reason", notes));
        return taskView(a.tenantId(), taskPublicId);
    }

    private void refreshTaskStatus(long tenantId, long taskId) {
        long open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM replenishment_task_lines
                WHERE tenant_id=? AND replenishment_task_id=? AND status<>'COMPLETED'
                """, Long.class, tenantId, taskId);
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM replenishment_task_lines
                WHERE tenant_id=? AND replenishment_task_id=?
                """, Long.class, tenantId, taskId);
        if (total > 0 && open == 0) {
            jdbc.update("""
                    UPDATE replenishment_tasks SET status='COMPLETED', completed_at=UTC_TIMESTAMP(6),
                        version=version+1, updated_at=UTC_TIMESTAMP(6)
                    WHERE id=? AND tenant_id=? AND status='IN_PROGRESS'
                    """, taskId, tenantId);
        } else {
            jdbc.update("""
                    UPDATE replenishment_tasks SET status='IN_PROGRESS', completed_at=NULL,
                        version=version+1, updated_at=UTC_TIMESTAMP(6)
                    WHERE id=? AND tenant_id=? AND status='COMPLETED'
                    """, taskId, tenantId);
        }
    }

    private WmsInventoryPostingService.PostingLeg postingLeg(Map<String, Object> line, String serial,
                                                               BigDecimal qty) {
        return new WmsInventoryPostingService.PostingLeg(string(line, "warehouse_public_id"),
                string(line, "source_location_public_id"), string(line, "item_public_id"),
                string(line, "lot_public_id"), serial, null, "AVAILABLE", qty);
    }

    private WmsInventoryPostingService.PostingLeg destinationPostingLeg(Map<String, Object> line, String serial,
                                                                          BigDecimal qty) {
        return new WmsInventoryPostingService.PostingLeg(string(line, "warehouse_public_id"),
                string(line, "destination_location_public_id"), string(line, "item_public_id"),
                string(line, "lot_public_id"), serial, null, "AVAILABLE", qty);
    }

    private WmsInventoryPostingService.PostingLeg reverseDestinationLeg(Map<String, Object> row, String serial) {
        return reverseDestinationLeg(row, serial, BigDecimal.ONE);
    }

    private WmsInventoryPostingService.PostingLeg reverseDestinationLeg(Map<String, Object> row, String serial,
                                                                          BigDecimal qty) {
        return new WmsInventoryPostingService.PostingLeg(string(row, "warehouse_public_id"),
                string(row, "destination_location_public_id"), string(row, "item_public_id"),
                string(row, "lot_public_id"), serial, null, "AVAILABLE", qty);
    }

    private WmsInventoryPostingService.PostingLeg reverseSourceLeg(Map<String, Object> row, String serial) {
        return reverseSourceLeg(row, serial, BigDecimal.ONE);
    }

    private WmsInventoryPostingService.PostingLeg reverseSourceLeg(Map<String, Object> row, String serial,
                                                                     BigDecimal qty) {
        return new WmsInventoryPostingService.PostingLeg(string(row, "warehouse_public_id"),
                string(row, "source_location_public_id"), string(row, "item_public_id"),
                string(row, "lot_public_id"), serial, null, "AVAILABLE", qty);
    }

    private Map<String, Object> taskView(long tenantId, String publicId) {
        Map<String, Object> task = requiredRow("""
                SELECT t.public_id, t.task_number, t.task_type, t.status, t.priority,
                       t.planned_at, t.released_at, t.started_at, t.completed_at, t.cancelled_at,
                       t.version, t.notes, w.public_id AS warehouse_public_id, w.warehouse_code,
                       r.public_id AS rule_public_id
                FROM replenishment_tasks t
                JOIN warehouses w ON w.id=t.warehouse_id AND w.tenant_id=t.tenant_id
                LEFT JOIN replenishment_rules r ON r.id=t.replenishment_rule_id AND r.tenant_id=t.tenant_id
                WHERE t.tenant_id=? AND t.public_id=?
                """, tenantId, required(publicId, "Task public ID"));
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT l.line_number, i.public_id AS item_public_id, i.sku, i.item_name,
                       sl.public_id AS source_location_public_id, sl.location_code AS source_location_code,
                       dl.public_id AS destination_location_public_id, dl.location_code AS destination_location_code,
                       lot.public_id AS lot_public_id, l.planned_qty, l.confirmed_qty, l.status
                FROM replenishment_task_lines l
                JOIN items i ON i.id=l.item_id AND i.tenant_id=l.tenant_id
                JOIN warehouse_locations sl ON sl.id=l.source_location_id AND sl.warehouse_id=l.warehouse_id AND sl.tenant_id=l.tenant_id
                JOIN warehouse_locations dl ON dl.id=l.destination_location_id AND dl.warehouse_id=l.warehouse_id AND dl.tenant_id=l.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id=l.lot_id AND lot.tenant_id=l.tenant_id AND lot.item_id=l.item_id
                WHERE l.tenant_id=? AND l.replenishment_task_id=(
                    SELECT id FROM replenishment_tasks WHERE tenant_id=? AND public_id=?
                )
                ORDER BY l.line_number
                """, tenantId, tenantId, publicId);
        Map<String, Object> result = new LinkedHashMap<>(task);
        result.put("lines", lines);
        return result;
    }

    private Map<String, Object> taskHeader(long tenantId, String publicId, boolean lock) {
        return requiredRow("SELECT * FROM replenishment_tasks WHERE tenant_id=? AND public_id=?"
                + (lock ? " FOR UPDATE" : ""), tenantId, required(publicId, "Task public ID"));
    }

    private Map<String, Object> taskLine(long tenantId, long taskId, String lineNumber, boolean lock) {
        return requiredRow("""
                SELECT l.*, i.tracking_method, i.public_id AS item_public_id,
                       w.public_id AS warehouse_public_id, sl.public_id AS source_location_public_id,
                       dl.public_id AS destination_location_public_id, lot.public_id AS lot_public_id
                FROM replenishment_task_lines l
                JOIN items i ON i.id=l.item_id AND i.tenant_id=l.tenant_id
                JOIN warehouses w ON w.id=l.warehouse_id AND w.tenant_id=l.tenant_id
                JOIN warehouse_locations sl ON sl.id=l.source_location_id AND sl.warehouse_id=l.warehouse_id AND sl.tenant_id=l.tenant_id
                JOIN warehouse_locations dl ON dl.id=l.destination_location_id AND dl.warehouse_id=l.warehouse_id AND dl.tenant_id=l.tenant_id
                LEFT JOIN inventory_lots lot ON lot.id=l.lot_id AND lot.tenant_id=l.tenant_id AND lot.item_id=l.item_id
                WHERE l.tenant_id=? AND l.replenishment_task_id=? AND l.line_number=?
                """ + (lock ? " FOR UPDATE" : ""), tenantId, taskId,
                Integer.parseInt(required(lineNumber, "Task line number")));
    }

    private void requireHasLines(long tenantId, long taskId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM replenishment_task_lines WHERE tenant_id=? AND replenishment_task_id=?",
                Long.class, tenantId, taskId) == 0) throw new IllegalStateException("Replenishment task has no lines");
    }

    private void requireVersion(Map<String, Object> row, long expected) {
        if (number(row, "version") != expected) throw new IllegalStateException("Task version is stale; reload and retry");
    }

    private void audit(Access access, String subject, String action,
                       Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("before", before);
        payload.put("after", after);
        final String json;
        try { json = mapper.writeValueAsString(payload); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Replenishment audit payload could not be serialized", e); }
        int written = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, operation_ref,
                   event_type, subject_public_id, change_payload)
                SELECT ?, t.id, m.id, 'TENANT_MEMBER', ?, 'WMS_REPLENISHMENT_TASK_UPDATED', ?, ?
                FROM tenants t
                JOIN tenant_memberships m ON m.id=? AND m.tenant_id=t.id AND m.status='ACTIVE'
                JOIN users u ON u.id=m.user_id AND u.public_id=? AND u.status='ACTIVE'
                WHERE t.id=? AND t.status='ACTIVE'
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), subject, json,
                access.membershipId(), access.userPublicId(), access.tenantId());
        if (written != 1) throw new AccessDeniedException("Audit actor is no longer active");
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
        if (!resolved.hasPermission(permission)) throw new AccessDeniedException("Required replenishment permission is missing");
        return new Access(principal, resolved.tenantId(), resolved.membershipId(), resolved.userPublicId());
    }

    private void lockTenant(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        List<Long> ids = jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE",
                Long.class, principal.tenantId());
        if (ids.isEmpty()) throw new AccessDeniedException("Active tenant is unavailable");
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new IllegalArgumentException("Replenishment task or referenced record not found");
        return rows.getFirst();
    }

    private long requiredLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        if (value == null) throw new IllegalStateException("Replenishment confirmation was not created");
        return value;
    }

    private String requiredLongString(String sql, Object... args) {
        String value = jdbc.queryForObject(sql, String.class, args);
        if (value == null) throw new IllegalStateException("Replenishment confirmation was not found");
        return value;
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException("Confirmation quantity must be positive");
        try { return value.setScale(4, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Quantity supports at most four decimal places", e); }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        String result = value.trim();
        if (result.length() > 100) throw new IllegalArgumentException(label + " is too long");
        return result;
    }

    private static String optional(String value) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > 1000) throw new IllegalArgumentException("Notes are too long");
        return result;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static String string(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }

    public record ConfirmCommand(UUID requestId, Integer lineNumber, BigDecimal quantity,
                                 List<String> serialPublicIds, String notes) {}
    public record ReverseCommand(UUID requestId, String notes) {}
    private record Access(WmsAuthenticatedPrincipal principal, long tenantId, long membershipId,
                          String userPublicId) {}
}
