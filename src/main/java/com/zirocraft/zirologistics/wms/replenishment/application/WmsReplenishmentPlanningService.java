package com.zirocraft.zirologistics.wms.replenishment.application;

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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-scoped replenishment policy and planning boundary.
 * Planning creates work documents only; it never reserves or posts inventory.
 */
@Service
@Profile("wms")
public class WmsReplenishmentPlanningService {
    private static final Set<String> RULE_TYPES = Set.of("MIN_MAX", "MANUAL");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");

    private final WmsMembershipAccessService accessService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WmsReplenishmentPlanningService(WmsMembershipAccessService accessService,
                                           JdbcTemplate jdbc, ObjectMapper mapper) {
        this.accessService = Objects.requireNonNull(accessService);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(WmsAuthenticatedPrincipal principal) {
        Access access = access(principal, "WMS_REPLENISHMENT_READ");
        return jdbc.queryForList("""
                SELECT r.public_id, r.rule_name, r.rule_type, r.minimum_qty, r.replenish_to_qty,
                       r.maximum_replenishment_qty, r.priority, r.is_active, r.notes, r.version,
                       w.public_id AS warehouse_public_id, w.warehouse_code,
                       i.public_id AS item_public_id, i.sku, i.item_name,
                       sl.public_id AS source_location_public_id, sl.location_code AS source_location_code,
                       dl.public_id AS destination_location_public_id, dl.location_code AS destination_location_code
                FROM replenishment_rules r
                JOIN warehouses w ON w.id = r.warehouse_id AND w.tenant_id = r.tenant_id
                JOIN items i ON i.id = r.item_id AND i.tenant_id = r.tenant_id
                JOIN warehouse_locations sl ON sl.id = r.source_location_id
                  AND sl.warehouse_id = r.warehouse_id AND sl.tenant_id = r.tenant_id
                JOIN warehouse_locations dl ON dl.id = r.destination_location_id
                  AND dl.warehouse_id = r.warehouse_id AND dl.tenant_id = r.tenant_id
                WHERE r.tenant_id = ?
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT 200
                """, access.tenantId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(WmsAuthenticatedPrincipal principal, String rulePublicId) {
        Access access = access(principal, "WMS_REPLENISHMENT_READ");
        return ruleView(access.tenantId(), rulePublicId);
    }

    @Transactional
    public Map<String, Object> create(WmsAuthenticatedPrincipal principal, RuleCommand command) {
        if (command == null) throw new IllegalArgumentException("Replenishment rule is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_REPLENISHMENT_RULE_MANAGE");
        RuleValues values = values(command);
        Scope scope = resolveScope(access.tenantId(), values);
        String publicId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO replenishment_rules
                      (public_id, tenant_id, warehouse_id, item_id, source_location_id,
                       destination_location_id, rule_name, rule_type, minimum_qty, replenish_to_qty,
                       maximum_replenishment_qty, priority, is_active, created_by_membership_id, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """, publicId, access.tenantId(), scope.warehouseId(), scope.itemId(),
                    scope.sourceLocationId(), scope.destinationLocationId(), values.name(), values.type(),
                    values.minimumQty(), values.targetQty(), values.maximumQty(), values.priority(),
                    access.membershipId(), values.notes());
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("A replenishment rule already exists for this item and location pair", duplicate);
        }
        audit(access, "WMS_REPLENISHMENT_RULE_UPDATED", publicId, "CREATE", null,
                Map.of("ruleType", values.type(), "warehousePublicId", values.warehousePublicId(),
                        "itemPublicId", values.itemPublicId()));
        return ruleView(access.tenantId(), publicId);
    }

    @Transactional
    public Map<String, Object> update(WmsAuthenticatedPrincipal principal, String rulePublicId,
                                      long expectedVersion, RuleCommand command) {
        if (command == null) throw new IllegalArgumentException("Replenishment rule is required");
        lockTenant(principal);
        Access access = access(principal, "WMS_REPLENISHMENT_RULE_MANAGE");
        RuleValues values = values(command);
        Map<String, Object> current = ruleHeader(access.tenantId(), rulePublicId, true);
        if (number(current, "version") != expectedVersion) {
            throw new IllegalStateException("Replenishment rule version is stale; reload and retry");
        }
        if (!bool(current.get("is_active"))) throw new IllegalStateException("Inactive replenishment rule cannot be updated");
        Scope scope = resolveScope(access.tenantId(), values);
        try {
            int changed = jdbc.update("""
                    UPDATE replenishment_rules
                    SET warehouse_id=?, item_id=?, source_location_id=?, destination_location_id=?,
                        rule_name=?, rule_type=?, minimum_qty=?, replenish_to_qty=?,
                        maximum_replenishment_qty=?, priority=?, notes=?, version=version+1,
                        updated_at=UTC_TIMESTAMP(6)
                    WHERE id=? AND tenant_id=? AND version=? AND is_active=1
                    """, scope.warehouseId(), scope.itemId(), scope.sourceLocationId(),
                    scope.destinationLocationId(), values.name(), values.type(), values.minimumQty(),
                    values.targetQty(), values.maximumQty(), values.priority(), values.notes(),
                    number(current, "id"), access.tenantId(), expectedVersion);
            if (changed != 1) throw new IllegalStateException("Replenishment rule changed; reload and retry");
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("A replenishment rule already exists for this item and location pair", duplicate);
        }
        audit(access, "WMS_REPLENISHMENT_RULE_UPDATED", rulePublicId, "UPDATE",
                Map.of("version", expectedVersion), Map.of("version", expectedVersion + 1));
        return ruleView(access.tenantId(), rulePublicId);
    }

    @Transactional
    public Map<String, Object> deactivate(WmsAuthenticatedPrincipal principal, String rulePublicId,
                                          long expectedVersion) {
        lockTenant(principal);
        Access access = access(principal, "WMS_REPLENISHMENT_RULE_MANAGE");
        Map<String, Object> current = ruleHeader(access.tenantId(), rulePublicId, true);
        if (number(current, "version") != expectedVersion) {
            throw new IllegalStateException("Replenishment rule version is stale; reload and retry");
        }
        if (!bool(current.get("is_active"))) return ruleView(access.tenantId(), rulePublicId);
        int changed = jdbc.update("""
                UPDATE replenishment_rules SET is_active=0, version=version+1, updated_at=UTC_TIMESTAMP(6)
                WHERE id=? AND tenant_id=? AND version=? AND is_active=1
                """, number(current, "id"), access.tenantId(), expectedVersion);
        if (changed != 1) throw new IllegalStateException("Replenishment rule changed; reload and retry");
        audit(access, "WMS_REPLENISHMENT_RULE_UPDATED", rulePublicId, "DEACTIVATE",
                Map.of("version", expectedVersion), Map.of("version", expectedVersion + 1));
        return ruleView(access.tenantId(), rulePublicId);
    }

    @Transactional
    public Map<String, Object> plan(WmsAuthenticatedPrincipal principal, String rulePublicId,
                                    PlanCommand command) {
        lockTenant(principal);
        Access access = access(principal, "WMS_REPLENISHMENT_RULE_MANAGE");
        requirePermission(principal, "WMS_REPLENISHMENT_TASK_CREATE");
        Map<String, Object> rule = ruleHeader(access.tenantId(), rulePublicId, true);
        if (!bool(rule.get("is_active"))) throw new IllegalStateException("Inactive replenishment rule cannot be planned");
        String type = string(rule, "rule_type");
        BigDecimal requested = command == null ? null : optionalQuantity(command.requestedQty());
        if ("MIN_MAX".equals(type) && requested != null) {
            throw new IllegalArgumentException("MIN_MAX planning calculates quantity from the configured threshold and target");
        }
        if ("MANUAL".equals(type) && requested == null) {
            throw new IllegalArgumentException("MANUAL rule planning requires requestedQty");
        }
        {
            List<Long> open = jdbc.queryForList("""
                    SELECT id FROM replenishment_tasks
                    WHERE tenant_id=? AND replenishment_rule_id=?
                      AND status IN ('DRAFT','READY','ASSIGNED','IN_PROGRESS')
                    ORDER BY id FOR UPDATE
                    """, Long.class, access.tenantId(), number(rule, "id"));
            if (!open.isEmpty()) throw new IllegalStateException("An open replenishment task already exists for this rule");
        }

        Map<String, Object> scope = requiredRow("""
                SELECT r.warehouse_id, r.item_id, r.source_location_id, r.destination_location_id,
                       r.priority, r.minimum_qty, r.replenish_to_qty, r.maximum_replenishment_qty,
                       w.public_id AS warehouse_public_id, w.status AS warehouse_status,
                       i.public_id AS item_public_id, i.tracking_method, i.status AS item_status, i.is_stock_item,
                       sl.public_id AS source_location_public_id, sl.status AS source_status,
                       dl.public_id AS destination_location_public_id, dl.status AS destination_status
                FROM replenishment_rules r
                JOIN warehouses w ON w.id=r.warehouse_id AND w.tenant_id=r.tenant_id
                JOIN items i ON i.id=r.item_id AND i.tenant_id=r.tenant_id
                JOIN warehouse_locations sl ON sl.id=r.source_location_id
                  AND sl.warehouse_id=r.warehouse_id AND sl.tenant_id=r.tenant_id
                JOIN warehouse_locations dl ON dl.id=r.destination_location_id
                  AND dl.warehouse_id=r.warehouse_id AND dl.tenant_id=r.tenant_id
                WHERE r.tenant_id=? AND r.id=?
                """, access.tenantId(), number(rule, "id"));
        if (!"ACTIVE".equals(string(scope, "warehouse_status"))
                || !"ACTIVE".equals(string(scope, "item_status"))
                || !bool(scope.get("is_stock_item"))
                || !"ACTIVE".equals(string(scope, "source_status"))
                || !"ACTIVE".equals(string(scope, "destination_status"))) {
            throw new IllegalStateException("Rule warehouse, item and locations must remain active stock resources");
        }

        long warehouseId = number(scope, "warehouse_id");
        long itemId = number(scope, "item_id");
        long sourceId = number(scope, "source_location_id");
        long destinationId = number(scope, "destination_location_id");
        String tracking = string(scope, "tracking_method");
        boolean lotTracked = Set.of("LOT", "LOT_AND_SERIAL").contains(tracking);

        BigDecimal destinationOnHand = scalar("""
                SELECT COALESCE(SUM(on_hand_qty),0)
                FROM inventory_balances
                WHERE tenant_id=? AND warehouse_id=? AND location_id=? AND item_id=?
                """, access.tenantId(), warehouseId, destinationId, itemId);
        BigDecimal desired;
        if ("MIN_MAX".equals(type)) {
            BigDecimal minimum = decimal(scope.get("minimum_qty"));
            if (destinationOnHand.compareTo(minimum) > 0) {
                throw new IllegalStateException("Destination stock is above the replenishment minimum");
            }
            desired = decimal(scope.get("replenish_to_qty")).subtract(destinationOnHand);
        } else {
            desired = requested;
        }
        BigDecimal maximum = nullableDecimal(scope.get("maximum_replenishment_qty"));
        if (maximum != null) desired = desired.min(maximum);
        if (desired.signum() <= 0) throw new IllegalStateException("No replenishment quantity is required");

        String lotFilter = lotTracked ? "AND b.lot_id IS NOT NULL" : "AND b.lot_id IS NULL";
        List<Map<String, Object>> sourceBuckets = jdbc.queryForList("""
                SELECT b.lot_id, l.public_id AS lot_public_id, b.available_qty
                FROM inventory_balances b
                LEFT JOIN inventory_lots l ON l.id=b.lot_id AND l.tenant_id=b.tenant_id AND l.item_id=b.item_id
                WHERE b.tenant_id=? AND b.warehouse_id=? AND b.location_id=? AND b.item_id=?
                  AND b.stock_status='AVAILABLE' AND b.available_qty > 0
                """ + lotFilter + (lotTracked ? " AND l.status='ACTIVE' " : "")
                + " ORDER BY b.lot_id ASC, b.id ASC FOR UPDATE",
                access.tenantId(), warehouseId, sourceId, itemId);
        if (sourceBuckets.isEmpty()) throw new IllegalStateException("Source location has no available stock for this item");
        BigDecimal totalAvailable = sourceBuckets.stream()
                .map(row -> decimal(row.get("available_qty"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal planned = desired.min(totalAvailable);
        if (planned.signum() <= 0) throw new IllegalStateException("No available source quantity can be planned");
        if (Set.of("SERIAL", "LOT_AND_SERIAL").contains(tracking)
                && planned.stripTrailingZeros().scale() > 0) {
            throw new IllegalStateException("Serial-tracked replenishment quantity must be a whole number");
        }

        List<PlannedBucket> allocations = new ArrayList<>();
        BigDecimal remaining = planned;
        for (Map<String, Object> bucket : sourceBuckets) {
            if (remaining.signum() <= 0) break;
            BigDecimal available = decimal(bucket.get("available_qty"));
            BigDecimal quantity = available.min(remaining);
            if (Set.of("SERIAL", "LOT_AND_SERIAL").contains(tracking)
                    && quantity.stripTrailingZeros().scale() > 0) {
                throw new IllegalStateException("Serial-tracked source bucket quantity is not a whole number");
            }
            if (quantity.signum() > 0) {
                allocations.add(new PlannedBucket(nullableNumber(bucket.get("lot_id")), quantity));
                remaining = remaining.subtract(quantity);
            }
        }
        if (remaining.signum() != 0) throw new IllegalStateException("Source allocation did not reconcile to the planned quantity");

        String taskPublicId = UUID.randomUUID().toString();
        String taskNumber = "RPL-" + LocalDate.now() + "-" + taskPublicId.substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("""
                INSERT INTO replenishment_tasks
                  (public_id, tenant_id, warehouse_id, replenishment_rule_id, task_number,
                   task_type, status, priority, created_by_membership_id, planned_at, notes)
                VALUES (?, ?, ?, ?, ?, ?, 'READY', ?, ?, UTC_TIMESTAMP(6), ?)
                """, taskPublicId, access.tenantId(), warehouseId, number(rule, "id"),
                taskNumber, "MIN_MAX".equals(type) ? "AUTOMATIC" : "MANUAL",
                string(scope, "priority"), access.membershipId(),
                command == null ? null : optional(command.notes(), 1000));
        long taskId = requiredLong("SELECT id FROM replenishment_tasks WHERE tenant_id=? AND public_id=?",
                access.tenantId(), taskPublicId);
        int lineNumber = 0;
        for (PlannedBucket bucket : allocations) {
            jdbc.update("""
                    INSERT INTO replenishment_task_lines
                      (tenant_id, replenishment_task_id, warehouse_id, item_id, source_location_id,
                       destination_location_id, lot_id, line_number, planned_qty, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """, access.tenantId(), taskId, warehouseId, itemId, sourceId, destinationId,
                    bucket.lotId(), ++lineNumber, bucket.quantity());
        }
        audit(access, "WMS_REPLENISHMENT_TASK_UPDATED", taskPublicId, "PLAN", null,
                Map.of("rulePublicId", rulePublicId, "plannedQty", planned.toPlainString(),
                        "sourceAvailableQty", totalAvailable.toPlainString(),
                        "destinationOnHandQty", destinationOnHand.toPlainString()));
        return taskView(access.tenantId(), taskPublicId);
    }

    private RuleValues values(RuleCommand command) {
        String type = enumValue(command.ruleType(), RULE_TYPES, "rule type");
        String priority = enumValue(command.priority() == null ? "NORMAL" : command.priority(),
                PRIORITIES, "priority");
        BigDecimal minimum = quantity(command.minimumQty(), false);
        BigDecimal target = quantity(command.replenishToQty(), true);
        if (target.compareTo(minimum) <= 0) throw new IllegalArgumentException("Replenish target must exceed minimum quantity");
        BigDecimal maximum = command.maximumReplenishmentQty() == null ? null
                : quantity(command.maximumReplenishmentQty(), true);
        return new RuleValues(required(command.warehousePublicId(), "Warehouse public ID", 36),
                required(command.itemPublicId(), "Item public ID", 36),
                required(command.sourceLocationPublicId(), "Source location public ID", 36),
                required(command.destinationLocationPublicId(), "Destination location public ID", 36),
                required(command.ruleName(), "Rule name", 150), type, minimum, target, maximum,
                priority, optional(command.notes(), 1000));
    }

    private Scope resolveScope(long tenantId, RuleValues values) {
        Map<String, Object> warehouse = requiredRow("""
                SELECT id, status FROM warehouses WHERE tenant_id=? AND public_id=?
                """, tenantId, values.warehousePublicId());
        long warehouseId = number(warehouse, "id");
        if (!"ACTIVE".equals(string(warehouse, "status"))) throw new IllegalArgumentException("Warehouse must be active");
        Map<String, Object> item = requiredRow("""
                SELECT id, status, is_stock_item FROM items WHERE tenant_id=? AND public_id=?
                """, tenantId, values.itemPublicId());
        if (!"ACTIVE".equals(string(item, "status")) || !bool(item.get("is_stock_item"))) {
            throw new IllegalArgumentException("Replenishment item must be an active stock item");
        }
        long itemId = number(item, "id");
        Map<String, Object> source = requiredRow("""
                SELECT id, status FROM warehouse_locations
                WHERE tenant_id=? AND warehouse_id=? AND public_id=?
                """, tenantId, warehouseId, values.sourceLocationPublicId());
        Map<String, Object> destination = requiredRow("""
                SELECT id, status FROM warehouse_locations
                WHERE tenant_id=? AND warehouse_id=? AND public_id=?
                """, tenantId, warehouseId, values.destinationLocationPublicId());
        if (!"ACTIVE".equals(string(source, "status")) || !"ACTIVE".equals(string(destination, "status"))) {
            throw new IllegalArgumentException("Source and destination locations must be active");
        }
        long sourceId = number(source, "id");
        long destinationId = number(destination, "id");
        if (sourceId == destinationId) throw new IllegalArgumentException("Source and destination locations must differ");
        return new Scope(warehouseId, itemId, sourceId, destinationId);
    }

    private Map<String, Object> ruleView(long tenantId, String publicId) {
        return requiredRow("""
                SELECT r.public_id, r.rule_name, r.rule_type, r.minimum_qty, r.replenish_to_qty,
                       r.maximum_replenishment_qty, r.priority, r.is_active, r.notes, r.version,
                       w.public_id AS warehouse_public_id, w.warehouse_code,
                       i.public_id AS item_public_id, i.sku, i.item_name,
                       sl.public_id AS source_location_public_id, sl.location_code AS source_location_code,
                       dl.public_id AS destination_location_public_id, dl.location_code AS destination_location_code
                FROM replenishment_rules r
                JOIN warehouses w ON w.id=r.warehouse_id AND w.tenant_id=r.tenant_id
                JOIN items i ON i.id=r.item_id AND i.tenant_id=r.tenant_id
                JOIN warehouse_locations sl ON sl.id=r.source_location_id AND sl.warehouse_id=r.warehouse_id AND sl.tenant_id=r.tenant_id
                JOIN warehouse_locations dl ON dl.id=r.destination_location_id AND dl.warehouse_id=r.warehouse_id AND dl.tenant_id=r.tenant_id
                WHERE r.tenant_id=? AND r.public_id=?
                """, tenantId, required(publicId, "Rule public ID", 36));
    }

    private Map<String, Object> taskView(long tenantId, String publicId) {
        Map<String, Object> task = requiredRow("""
                SELECT t.public_id, t.task_number, t.task_type, t.status, t.priority, t.planned_at,
                       t.created_at, t.updated_at, r.public_id AS rule_public_id,
                       w.public_id AS warehouse_public_id, w.warehouse_code
                FROM replenishment_tasks t
                JOIN warehouses w ON w.id=t.warehouse_id AND w.tenant_id=t.tenant_id
                LEFT JOIN replenishment_rules r ON r.id=t.replenishment_rule_id AND r.tenant_id=t.tenant_id
                WHERE t.tenant_id=? AND t.public_id=?
                """, tenantId, publicId);
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
                JOIN replenishment_tasks t ON t.id=l.replenishment_task_id AND t.tenant_id=l.tenant_id
                WHERE l.tenant_id=? AND t.public_id=?
                ORDER BY l.line_number
                """, tenantId, publicId);
        Map<String, Object> result = new LinkedHashMap<>(task);
        result.put("lines", lines);
        return result;
    }

    private Map<String, Object> ruleHeader(long tenantId, String publicId, boolean lock) {
        String sql = "SELECT * FROM replenishment_rules WHERE tenant_id=? AND public_id=?"
                + (lock ? " FOR UPDATE" : "");
        return requiredRow(sql, tenantId, required(publicId, "Rule public ID", 36));
    }

    private void audit(Access access, String eventType, String subject, String action,
                       Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        if (before != null) payload.put("before", before);
        if (after != null) payload.put("after", after);
        final String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Replenishment audit payload could not be serialized", failure);
        }
        int written = jdbc.update("""
                INSERT INTO tenant_audit_events
                  (public_id, tenant_id, actor_membership_id, actor_type, operation_ref,
                   event_type, subject_public_id, change_payload)
                SELECT ?, t.id, m.id, 'TENANT_MEMBER', ?, ?, ?, ?
                FROM tenants t
                JOIN tenant_memberships m ON m.id=? AND m.tenant_id=t.id AND m.status='ACTIVE'
                JOIN users u ON u.id=m.user_id AND u.public_id=? AND u.status='ACTIVE'
                WHERE t.id=? AND t.status='ACTIVE'
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(), eventType, subject,
                json, access.membershipId(), access.userPublicId(), access.tenantId());
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
        return new Access(resolved.tenantId(), resolved.membershipId(), resolved.userPublicId());
    }

    private void requirePermission(WmsAuthenticatedPrincipal principal, String permission) {
        WmsMembershipAccess resolved = accessService.resolve(principal.userPublicId(), principal.membershipPublicId());
        if (!resolved.hasPermission(permission)) throw new AccessDeniedException("Required replenishment permission is missing");
    }

    private void lockTenant(WmsAuthenticatedPrincipal principal) {
        if (principal == null) throw new AccessDeniedException("Tenant context is required");
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE", Long.class, principal.tenantId());
        if (ids.isEmpty()) throw new AccessDeniedException("Active tenant is unavailable");
    }

    private Map<String, Object> requiredRow(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new IllegalArgumentException("Replenishment rule or referenced record not found");
        return rows.getFirst();
    }

    private long requiredLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        if (value == null) throw new IllegalStateException("Replenishment task was not created");
        return value;
    }

    private BigDecimal scalar(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO.setScale(4) : value;
    }

    private static BigDecimal quantity(BigDecimal value, boolean positive) {
        if (value == null || (positive ? value.signum() <= 0 : value.signum() < 0)) {
            throw new IllegalArgumentException(positive ? "Quantity must be positive" : "Quantity cannot be negative");
        }
        try {
            return value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Quantity supports at most four decimal places", failure);
        }
    }

    private static BigDecimal optionalQuantity(BigDecimal value) {
        return value == null ? null : quantity(value, true);
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
        if (normalized.length() > max) throw new IllegalArgumentException("Optional text is too long");
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

    private static BigDecimal nullableDecimal(Object value) {
        return value == null ? null : decimal(value);
    }

    private static String string(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }

    public record RuleCommand(String warehousePublicId, String itemPublicId,
                              String sourceLocationPublicId, String destinationLocationPublicId,
                              String ruleName, String ruleType, BigDecimal minimumQty,
                              BigDecimal replenishToQty, BigDecimal maximumReplenishmentQty,
                              String priority, String notes) {}
    public record PlanCommand(BigDecimal requestedQty, String notes) {}
    private record RuleValues(String warehousePublicId, String itemPublicId,
                              String sourceLocationPublicId, String destinationLocationPublicId,
                              String name, String type, BigDecimal minimumQty, BigDecimal targetQty,
                              BigDecimal maximumQty, String priority, String notes) {}
    private record Scope(long warehouseId, long itemId, long sourceLocationId, long destinationLocationId) {}
    private record PlannedBucket(Long lotId, BigDecimal quantity) {}
    private record Access(long tenantId, long membershipId, String userPublicId) {}
}
