package com.zirocraft.zirologistics.wms.replenishment.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsReplenishmentPlanningMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_replenishment_test")
            .withUsername("zirowms_replenishment_test")
            .withPassword("zirowms_replenishment_test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsReplenishmentPlanningService service;
    @Autowired private WmsReplenishmentTaskService taskService;
    @Autowired private WmsInventoryPostingService postingService;
    @Autowired private JdbcTemplate jdbc;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture("NONE");
    }

    @Test
    void minMaxPlanningUsesDestinationOnHandCapsByRuleAndSourceAvailableWithoutReserving() {
        receive(fixture, fixture.sourceLocationPublicId(), "10.0000");
        receive(fixture, fixture.destinationLocationPublicId(), "2.0000");
        String ruleId = createRule(fixture, "MIN_MAX", "3.0000", "8.0000", "5.0000");

        Map<String, Object> task = service.plan(fixture.principal(), ruleId, null);

        assertEquals("READY", task.get("status"));
        assertEquals("AUTOMATIC", task.get("task_type"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) task.get("lines");
        assertEquals(1, lines.size());
        assertEquals(new BigDecimal("5.0000"), lines.getFirst().get("planned_qty"));
        assertEquals(new BigDecimal("10.0000"), available(fixture, fixture.sourceLocationPublicId()));
        assertEquals(new BigDecimal("2.0000"), onHand(fixture, fixture.destinationLocationPublicId()));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=? AND movement_type='TRANSFER'",
                fixture.tenantId()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id=? AND subject_public_id=? AND event_type='WMS_REPLENISHMENT_TASK_UPDATED'
                """, fixture.tenantId(), task.get("public_id")));
    }

    @Test
    void insufficientSourceStockPlansOnlySafelyAvailableQuantity() {
        receive(fixture, fixture.sourceLocationPublicId(), "2.0000");
        String ruleId = createRule(fixture, "MIN_MAX", "3.0000", "8.0000", null);

        Map<String, Object> task = service.plan(fixture.principal(), ruleId, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) task.get("lines");

        assertEquals(new BigDecimal("2.0000"), lines.getFirst().get("planned_qty"));
        assertEquals(new BigDecimal("2.0000"), available(fixture, fixture.sourceLocationPublicId()));
        assertEquals(new BigDecimal("0.0000"), onHand(fixture, fixture.destinationLocationPublicId()));
    }

    @Test
    void oneOpenTaskPerRuleIsEnforcedUnderTheRuleLock() {
        receive(fixture, fixture.sourceLocationPublicId(), "10.0000");
        String ruleId = createRule(fixture, "MIN_MAX", "2.0000", "6.0000", null);

        service.plan(fixture.principal(), ruleId, null);
        assertThrows(IllegalStateException.class, () -> service.plan(fixture.principal(), ruleId, null));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM replenishment_tasks
                WHERE tenant_id=? AND replenishment_rule_id=(
                  SELECT id FROM replenishment_rules WHERE tenant_id=? AND public_id=?
                ) AND status IN ('DRAFT','READY','ASSIGNED','IN_PROGRESS')
                """, fixture.tenantId(), fixture.tenantId(), ruleId));
    }

    @Test
    void manualRuleRequiresExplicitQuantityAndCreatesManualTask() {
        receive(fixture, fixture.sourceLocationPublicId(), "7.0000");
        String ruleId = createRule(fixture, "MANUAL", "0.0000", "10.0000", "4.0000");

        assertThrows(IllegalArgumentException.class, () -> service.plan(fixture.principal(), ruleId, null));
        Map<String, Object> task = service.plan(fixture.principal(), ruleId,
                new WmsReplenishmentPlanningService.PlanCommand(new BigDecimal("6.0000"), "Manual replenishment"));
        assertEquals("MANUAL", task.get("task_type"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) task.get("lines");
        assertEquals(new BigDecimal("4.0000"), lines.getFirst().get("planned_qty"));
    }

    @Test
    void ruleMutationsAreTenantScopedAndUseOptimisticVersion() {
        String ruleId = createRule(fixture, "MIN_MAX", "1.0000", "5.0000", null);
        Map<String, Object> rule = service.get(fixture.principal(), ruleId);
        assertEquals(0L, ((Number) rule.get("version")).longValue());

        var updated = service.update(fixture.principal(), ruleId, 0L,
                new WmsReplenishmentPlanningService.RuleCommand(
                        fixture.warehousePublicId(), fixture.itemPublicId(),
                        fixture.sourceLocationPublicId(), fixture.destinationLocationPublicId(),
                        "Updated policy", "MIN_MAX", new BigDecimal("2.0000"),
                        new BigDecimal("7.0000"), null, "HIGH", "updated"));
        assertEquals("Updated policy", updated.get("rule_name"));
        assertEquals(1L, ((Number) updated.get("version")).longValue());
        assertThrows(IllegalStateException.class, () -> service.deactivate(fixture.principal(), ruleId, 0L));
        var inactive = service.deactivate(fixture.principal(), ruleId, 1L);
        assertEquals(Boolean.FALSE, inactive.get("is_active"));
        assertThrows(IllegalStateException.class, () -> service.plan(fixture.principal(), ruleId, null));
    }

    @Test
    void missingRulePermissionIsDeniedByPersistedMembershipAccess() {
        Fixture restricted = fixture("NONE");
        jdbc.update("""
                DELETE rp FROM role_permissions rp
                JOIN permissions p ON p.id=rp.permission_id
                JOIN membership_roles mr ON mr.role_id=rp.role_id
                WHERE mr.tenant_id=? AND mr.membership_id=?
                  AND p.permission_code='WMS_REPLENISHMENT_RULE_MANAGE'
                """, restricted.tenantId(), restricted.principal().membershipId());
        assertThrows(AccessDeniedException.class, () -> service.create(restricted.principal(),
                ruleCommand(restricted, "MIN_MAX", "1.0000", "5.0000", null)));
    }

    @Test
    void lotTrackedPlanningCreatesOneTaskLinePerAvailableActiveLot() {
        Fixture lotFixture = fixture("LOT");
        String firstLot = createLot(lotFixture, "LOT-A");
        String secondLot = createLot(lotFixture, "LOT-B");
        receiveLot(lotFixture, lotFixture.sourceLocationPublicId(), firstLot, "2.0000");
        receiveLot(lotFixture, lotFixture.sourceLocationPublicId(), secondLot, "3.0000");
        String ruleId = createRule(lotFixture, "MIN_MAX", "1.0000", "5.0000", null);

        Map<String, Object> task = service.plan(lotFixture.principal(), ruleId, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) task.get("lines");

        assertEquals(2, lines.size());
        assertEquals(List.of(new BigDecimal("2.0000"), new BigDecimal("3.0000")),
                lines.stream().map(line -> (BigDecimal) line.get("planned_qty")).toList());
        assertEquals(Set.of(firstLot, secondLot),
                lines.stream().map(line -> (String) line.get("lot_public_id")).collect(java.util.stream.Collectors.toSet()));
    }


    @Test
    void taskLifecycleSupportsPartialConfirmationAndOneTimeAtomicReversal() {
        receive(fixture, fixture.sourceLocationPublicId(), "10.0000");
        String ruleId = createRule(fixture, "MIN_MAX", "3.0000", "8.0000", null);
        Map<String, Object> task = service.plan(fixture.principal(), ruleId, null);
        String taskId = (String) task.get("public_id");

        task = taskService.release(fixture.principal(), taskId, 0L);
        assertEquals("READY", task.get("status"));
        task = taskService.assign(fixture.principal(), taskId, 1L,
                fixture.principal().membershipPublicId());
        assertEquals("ASSIGNED", task.get("status"));
        task = taskService.start(fixture.principal(), taskId, 2L);
        assertEquals("IN_PROGRESS", task.get("status"));

        var first = new WmsReplenishmentTaskService.ConfirmCommand(
                UUID.randomUUID(), 1, new BigDecimal("3.0000"), List.of(), "Partial move");
        task = taskService.confirm(fixture.principal(), taskId, first);
        assertEquals("IN_PROGRESS", task.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterFirst = (List<Map<String, Object>>) task.get("lines");
        assertEquals(new BigDecimal("3.0000"), afterFirst.getFirst().get("confirmed_qty"));
        assertEquals("PARTIALLY_COMPLETED", afterFirst.getFirst().get("status"));

        var second = new WmsReplenishmentTaskService.ConfirmCommand(
                UUID.randomUUID(), 1, new BigDecimal("5.0000"), List.of(), "Complete move");
        task = taskService.confirm(fixture.principal(), taskId, second);
        assertEquals("COMPLETED", task.get("status"));
        assertEquals(new BigDecimal("2.0000"), onHand(fixture, fixture.sourceLocationPublicId()));
        assertEquals(new BigDecimal("8.0000"), onHand(fixture, fixture.destinationLocationPublicId()));
        assertEquals(4, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id=? AND movement_type='TRANSFER'
                """, fixture.tenantId()));

        String firstConfirmation = jdbc.queryForObject("""
                SELECT public_id FROM replenishment_confirmations
                WHERE tenant_id=? AND request_id=? AND confirmation_type='MOVE'
                """, String.class, fixture.tenantId(), first.requestId().toString());
        task = taskService.reverse(fixture.principal(), firstConfirmation,
                new WmsReplenishmentTaskService.ReverseCommand(UUID.randomUUID(), "Reverse first partial move"));
        assertEquals("IN_PROGRESS", task.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> afterReverse = (List<Map<String, Object>>) task.get("lines");
        assertEquals(new BigDecimal("5.0000"), afterReverse.getFirst().get("confirmed_qty"));
        assertEquals(new BigDecimal("5.0000"), onHand(fixture, fixture.sourceLocationPublicId()));
        assertEquals(new BigDecimal("5.0000"), onHand(fixture, fixture.destinationLocationPublicId()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM replenishment_confirmations
                WHERE tenant_id=? AND reverses_confirmation_id=(
                    SELECT id FROM replenishment_confirmations WHERE tenant_id=? AND public_id=?
                )
                """, fixture.tenantId(), fixture.tenantId(), firstConfirmation));
        assertThrows(IllegalStateException.class, () -> taskService.reverse(fixture.principal(),
                firstConfirmation, new WmsReplenishmentTaskService.ReverseCommand(
                        UUID.randomUUID(), "Duplicate reversal")));
    }

    @Test
    void confirmationRejectsQuantityBeyondRemainingPlanWithoutPostingStock() {
        receive(fixture, fixture.sourceLocationPublicId(), "10.0000");
        String ruleId = createRule(fixture, "MIN_MAX", "3.0000", "8.0000", null);
        Map<String, Object> task = service.plan(fixture.principal(), ruleId, null);
        String taskId = (String) task.get("public_id");
        taskService.release(fixture.principal(), taskId, 0L);
        taskService.assign(fixture.principal(), taskId, 1L, fixture.principal().membershipPublicId());
        taskService.start(fixture.principal(), taskId, 2L);

        assertThrows(IllegalArgumentException.class, () -> taskService.confirm(fixture.principal(), taskId,
                new WmsReplenishmentTaskService.ConfirmCommand(
                        UUID.randomUUID(), 1, new BigDecimal("9.0000"), List.of(), "Too much")));
        assertEquals(new BigDecimal("10.0000"), onHand(fixture, fixture.sourceLocationPublicId()));
        assertEquals(new BigDecimal("0.0000"), onHand(fixture, fixture.destinationLocationPublicId()));
        assertEquals(0, count("SELECT COUNT(*) FROM replenishment_confirmations WHERE tenant_id=?",
                fixture.tenantId()));
    }


    @Test
    void serialConfirmationAndReversalMoveTheExactIdentityBetweenLocations() {
        Fixture serialFixture = fixture("SERIAL");
        String serialNumber = "RPL-SN-" + UUID.randomUUID();
        postingService.post(serialFixture.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "RECEIPT",
                List.of(new WmsInventoryPostingService.PostingLeg(
                        serialFixture.warehousePublicId(), serialFixture.sourceLocationPublicId(),
                        serialFixture.itemPublicId(), null, null, serialNumber,
                        "AVAILABLE", BigDecimal.ONE)),
                "Serial replenishment test receipt"));
        String serialPublicId = jdbc.queryForObject("""
                SELECT public_id FROM inventory_serials WHERE tenant_id=? AND serial_number=?
                """, String.class, serialFixture.tenantId(), serialNumber);

        String ruleId = createRule(serialFixture, "MIN_MAX", "0.0000", "1.0000", null);
        Map<String, Object> task = service.plan(serialFixture.principal(), ruleId, null);
        String taskId = (String) task.get("public_id");
        taskService.release(serialFixture.principal(), taskId, 0L);
        taskService.assign(serialFixture.principal(), taskId, 1L,
                serialFixture.principal().membershipPublicId());
        taskService.start(serialFixture.principal(), taskId, 2L);
        task = taskService.confirm(serialFixture.principal(), taskId,
                new WmsReplenishmentTaskService.ConfirmCommand(
                        UUID.randomUUID(), 1, BigDecimal.ONE, List.of(serialPublicId), "Move exact serial"));
        assertEquals("COMPLETED", task.get("status"));
        assertEquals(serialFixture.destinationLocationId(), jdbc.queryForObject("""
                SELECT current_location_id FROM inventory_serials WHERE tenant_id=? AND public_id=?
                """, Long.class, serialFixture.tenantId(), serialPublicId));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM replenishment_confirmation_serials cs
                JOIN replenishment_confirmations c ON c.id=cs.confirmation_id AND c.tenant_id=cs.tenant_id
                WHERE c.tenant_id=? AND c.confirmation_type='MOVE'
                """, serialFixture.tenantId()));

        String confirmationPublicId = jdbc.queryForObject("""
                SELECT public_id FROM replenishment_confirmations
                WHERE tenant_id=? AND confirmation_type='MOVE'
                """, String.class, serialFixture.tenantId());
        task = taskService.reverse(serialFixture.principal(), confirmationPublicId,
                new WmsReplenishmentTaskService.ReverseCommand(UUID.randomUUID(), "Return serial to source"));
        assertEquals("IN_PROGRESS", task.get("status"));
        assertEquals(serialFixture.sourceLocationId(), jdbc.queryForObject("""
                SELECT current_location_id FROM inventory_serials WHERE tenant_id=? AND public_id=?
                """, Long.class, serialFixture.tenantId(), serialPublicId));
        assertEquals(2, count("""
                SELECT COUNT(*) FROM replenishment_confirmation_serials cs
                JOIN replenishment_confirmations c ON c.id=cs.confirmation_id AND c.tenant_id=cs.tenant_id
                WHERE c.tenant_id=?
                """, serialFixture.tenantId()));
    }


    @Test
    void genericTransferCannotCrossWarehouseBoundary() {
        receive(fixture, fixture.sourceLocationPublicId(), "2.0000");
        String otherWarehousePublicId = uuid();
        String otherLocationPublicId = uuid();
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, 'Other Warehouse', 'ACTIVE')
                """, otherWarehousePublicId, fixture.tenantId(),
                "RPL_OTHER_" + otherWarehousePublicId.substring(0, 8));
        long otherWarehouseId = jdbc.queryForObject(
                "SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?",
                Long.class, fixture.tenantId(), otherWarehousePublicId);
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, 'Other Destination', 'STORAGE', 'ACTIVE')
                """, otherLocationPublicId, fixture.tenantId(), otherWarehouseId,
                "RPL_OTHER_LOC_" + otherLocationPublicId.substring(0, 8));

        WmsInventoryPostingService.PostingLeg source = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), fixture.sourceLocationPublicId(), fixture.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("1.0000"));
        WmsInventoryPostingService.PostingLeg destination = new WmsInventoryPostingService.PostingLeg(
                otherWarehousePublicId, otherLocationPublicId, fixture.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("1.0000"));
        assertThrows(IllegalArgumentException.class, () -> postingService.post(fixture.principal(),
                new WmsInventoryPostingService.PostingCommand(
                        UUID.randomUUID(), "TRANSFER", List.of(source, destination), "Cross-warehouse spoof")));

        assertEquals(new BigDecimal("2.0000"), onHand(fixture, fixture.sourceLocationPublicId()));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=? AND movement_type='TRANSFER'",
                fixture.tenantId()));
    }

    private String createRule(Fixture f, String type, String minimum, String target, String maximum) {
        Map<String, Object> rule = service.create(f.principal(),
                ruleCommand(f, type, minimum, target, maximum));
        return (String) rule.get("public_id");
    }

    private WmsReplenishmentPlanningService.RuleCommand ruleCommand(
            Fixture f, String type, String minimum, String target, String maximum) {
        return new WmsReplenishmentPlanningService.RuleCommand(
                f.warehousePublicId(), f.itemPublicId(), f.sourceLocationPublicId(),
                f.destinationLocationPublicId(), "Primary replenishment", type,
                new BigDecimal(minimum), new BigDecimal(target),
                maximum == null ? null : new BigDecimal(maximum), "NORMAL", "integration test");
    }

    private String createLot(Fixture f, String lotNumber) {
        String publicId = uuid();
        jdbc.update("""
                INSERT INTO inventory_lots (public_id, tenant_id, item_id, lot_number, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, publicId, f.tenantId(), f.itemId(), lotNumber + "_" + publicId.substring(0, 8));
        return publicId;
    }

    private void receiveLot(Fixture f, String locationPublicId, String lotPublicId, String quantity) {
        postingService.post(f.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "RECEIPT",
                List.of(new WmsInventoryPostingService.PostingLeg(
                        f.warehousePublicId(), locationPublicId, f.itemPublicId(),
                        lotPublicId, null, null, "AVAILABLE", new BigDecimal(quantity))),
                "Lot replenishment planning test receipt"));
    }

    private void receive(Fixture f, String locationPublicId, String quantity) {
        postingService.post(f.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "RECEIPT",
                List.of(new WmsInventoryPostingService.PostingLeg(
                        f.warehousePublicId(), locationPublicId, f.itemPublicId(),
                        null, null, null, "AVAILABLE", new BigDecimal(quantity))),
                "Replenishment planning test receipt"));
    }

    private BigDecimal onHand(Fixture f, String locationPublicId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand_qty),0) FROM inventory_balances
                WHERE tenant_id=? AND warehouse_id=? AND location_id=(
                  SELECT id FROM warehouse_locations WHERE tenant_id=? AND public_id=?
                ) AND item_id=? AND stock_status='AVAILABLE'
                """, BigDecimal.class, f.tenantId(), f.warehouseId(), f.tenantId(),
                locationPublicId, f.itemId());
        return value;
    }

    private BigDecimal available(Fixture f, String locationPublicId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(available_qty),0) FROM inventory_balances
                WHERE tenant_id=? AND warehouse_id=? AND location_id=(
                  SELECT id FROM warehouse_locations WHERE tenant_id=? AND public_id=?
                ) AND item_id=? AND stock_status='AVAILABLE'
                """, BigDecimal.class, f.tenantId(), f.warehouseId(), f.tenantId(),
                locationPublicId, f.itemId());
        return value;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private Fixture fixture(String trackingMethod) {
        String tenantPublicId = uuid();
        String userPublicId = uuid();
        String membershipPublicId = uuid();
        String warehousePublicId = uuid();
        String sourcePublicId = uuid();
        String destinationPublicId = uuid();
        String itemPublicId = uuid();
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Replenishment Test Legal', 'Replenishment Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, tenantPublicId, "RPL_" + tenantPublicId.substring(0, 8));
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE public_id=?", Long.class, tenantPublicId);
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', 'Replenishment Test User', 'ACTIVE')
                """, userPublicId, "replenishment-" + userPublicId + "@example.test");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE public_id=?", Long.class, userPublicId);
        jdbc.update("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status, joined_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                """, membershipPublicId, tenantId, userId);
        long membershipId = jdbc.queryForObject("SELECT id FROM tenant_memberships WHERE public_id=?",
                Long.class, membershipPublicId);
        String rolePublicId = uuid();
        jdbc.update("""
                INSERT INTO roles (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                VALUES (?, ?, ?, 'Replenishment Test Role', 'Integration test role', 0, 'ACTIVE')
                """, rolePublicId, tenantId, "RPL_TEST_" + tenantPublicId.substring(0, 8));
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE public_id=?", Long.class, rolePublicId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenantId, membershipId, roleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE permission_code IN (
                  'WMS_INVENTORY_POST','WMS_REPLENISHMENT_READ',
                  'WMS_REPLENISHMENT_RULE_MANAGE','WMS_REPLENISHMENT_TASK_CREATE',
                  'WMS_REPLENISHMENT_TASK_RELEASE','WMS_REPLENISHMENT_TASK_ASSIGN',
                  'WMS_REPLENISHMENT_TASK_START','WMS_REPLENISHMENT_TASK_CONFIRM',
                  'WMS_REPLENISHMENT_TASK_CANCEL','WMS_REPLENISHMENT_CONFIRMATION_REVERSE'
                )
                """, roleId);
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, 'Replenishment Warehouse', 'ACTIVE')
                """, warehousePublicId, tenantId, "RPL_WH_" + tenantPublicId.substring(0, 8));
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE public_id=?",
                Long.class, warehousePublicId);
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, 'Replenishment Source', 'STORAGE', 'ACTIVE'),
                       (?, ?, ?, ?, 'Replenishment Destination', 'PICKING', 'ACTIVE')
                """, sourcePublicId, tenantId, warehouseId, "SRC_" + tenantPublicId.substring(0, 8),
                destinationPublicId, tenantId, warehouseId, "DST_" + tenantPublicId.substring(0, 8));
        long sourceId = jdbc.queryForObject("SELECT id FROM warehouse_locations WHERE public_id=?",
                Long.class, sourcePublicId);
        long destinationId = jdbc.queryForObject("SELECT id FROM warehouse_locations WHERE public_id=?",
                Long.class, destinationPublicId);
        jdbc.update("""
                INSERT INTO items (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method,
                    is_stock_item, status)
                SELECT ?, ?, ?, 'Replenishment Item', id, ?, 1, 'ACTIVE'
                FROM units_of_measure WHERE uom_code='EA'
                """, itemPublicId, tenantId, "RPL_SKU_" + tenantPublicId.substring(0, 8), trackingMethod);
        long itemId = jdbc.queryForObject("SELECT id FROM items WHERE public_id=?", Long.class, itemPublicId);
        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                userPublicId, tenantId, tenantPublicId, membershipId, membershipPublicId, Set.of());
        return new Fixture(tenantId, warehouseId, warehousePublicId, sourceId, sourcePublicId,
                destinationId, destinationPublicId, itemId, itemPublicId, principal);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(long tenantId, long warehouseId, String warehousePublicId,
                           long sourceLocationId, String sourceLocationPublicId,
                           long destinationLocationId, String destinationLocationPublicId,
                           long itemId, String itemPublicId, WmsAuthenticatedPrincipal principal) {}
}
