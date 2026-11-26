package com.zirocraft.zirologistics.wms.putaway.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.inbound.application.WmsInboundReceivingService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsPutawayManagementMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_putaway_test")
            .withUsername("zirowms_putaway_test")
            .withPassword("zirowms_putaway_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsPutawayManagementService putawayService;
    @Autowired private WmsInboundReceivingService inboundService;
    @Autowired private JdbcTemplate jdbc;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture("NONE");
    }

    @Test
    void partialConfirmationsMoveStockAndCompleteTaskOnlyAfterPlannedQuantityIsMoved() {
        Receipt receipt = receive(fixture, "NONE", "5.0000", List.of());
        String taskId = createTask(fixture, receipt, "5.0000", List.of());
        String lineId = taskLineId(fixture, taskId);
        startTask(fixture, taskId);

        putawayService.confirm(fixture.principal(), taskId, lineId,
                new WmsPutawayManagementService.ConfirmPutawayCommand(
                        UUID.randomUUID(), new BigDecimal("2.0000"), List.of(), "First pallet"));
        assertEquals(new BigDecimal("3.0000"), balance(fixture, fixture.receivingLocationPublicId()));
        assertEquals(new BigDecimal("2.0000"), balance(fixture, fixture.storageLocationPublicId()));
        assertEquals("IN_PROGRESS", taskStatus(fixture, taskId));
        assertEquals("IN_PROGRESS", lineStatus(fixture, lineId));

        putawayService.confirm(fixture.principal(), taskId, lineId,
                new WmsPutawayManagementService.ConfirmPutawayCommand(
                        UUID.randomUUID(), new BigDecimal("3.0000"), List.of(), "Final pallet"));
        assertEquals(BigDecimal.ZERO.setScale(4), balance(fixture, fixture.receivingLocationPublicId()));
        assertEquals(new BigDecimal("5.0000"), balance(fixture, fixture.storageLocationPublicId()));
        assertEquals("COMPLETED", taskStatus(fixture, taskId));
        assertEquals("COMPLETED", lineStatus(fixture, lineId));
        assertEquals(5, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(2, count("""
                SELECT COUNT(*) FROM putaway_confirmations
                WHERE tenant_id = ? AND putaway_task_id =
                  (SELECT id FROM putaway_tasks WHERE tenant_id = ? AND public_id = ?)
                """, fixture.tenantId(), fixture.tenantId(), taskId));
    }

    @Test
    void retryingTheSamePutawayRequestReturnsOriginalConfirmationWithoutMovingStockAgain() {
        Receipt receipt = receive(fixture, "NONE", "2.0000", List.of());
        String taskId = createTask(fixture, receipt, "2.0000", List.of());
        String lineId = taskLineId(fixture, taskId);
        startTask(fixture, taskId);
        UUID requestId = UUID.randomUUID();
        WmsPutawayManagementService.ConfirmPutawayCommand command =
                new WmsPutawayManagementService.ConfirmPutawayCommand(
                        requestId, new BigDecimal("2.0000"), List.of(), "Retry-safe confirmation");

        var first = putawayService.confirm(fixture.principal(), taskId, lineId, command);
        var retry = putawayService.confirm(fixture.principal(), taskId, lineId, command);

        assertEquals(first.get("confirmationPublicId"), retry.get("confirmationPublicId"));
        assertEquals(BigDecimal.ZERO.setScale(4), balance(fixture, fixture.receivingLocationPublicId()));
        assertEquals(new BigDecimal("2.0000"), balance(fixture, fixture.storageLocationPublicId()));
        assertEquals(new BigDecimal("2.0000"), jdbc.queryForObject("""
                SELECT confirmed_qty FROM putaway_task_lines WHERE tenant_id = ? AND public_id = ?
                """, BigDecimal.class, fixture.tenantId(), lineId));
        assertEquals("COMPLETED", taskStatus(fixture, taskId));
        assertEquals("COMPLETED", lineStatus(fixture, lineId));
        assertEquals(3, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(1, count("SELECT COUNT(*) FROM putaway_confirmations WHERE tenant_id = ? AND request_id = ?",
                fixture.tenantId(), requestId.toString()));
    }

    @Test
    void reusingPutawayRequestIdWithDifferentQuantityIsRejectedWithoutAdditionalMovement() {
        Receipt receipt = receive(fixture, "NONE", "5.0000", List.of());
        String taskId = createTask(fixture, receipt, "5.0000", List.of());
        String lineId = taskLineId(fixture, taskId);
        startTask(fixture, taskId);
        UUID requestId = UUID.randomUUID();

        putawayService.confirm(fixture.principal(), taskId, lineId,
                new WmsPutawayManagementService.ConfirmPutawayCommand(
                        requestId, new BigDecimal("2.0000"), List.of(), null));

        assertThrows(IllegalArgumentException.class, () -> putawayService.confirm(
                fixture.principal(), taskId, lineId,
                new WmsPutawayManagementService.ConfirmPutawayCommand(
                        requestId, new BigDecimal("3.0000"), List.of(), null)));

        assertEquals(new BigDecimal("3.0000"), balance(fixture, fixture.receivingLocationPublicId()));
        assertEquals(new BigDecimal("2.0000"), balance(fixture, fixture.storageLocationPublicId()));
        assertEquals(new BigDecimal("2.0000"), jdbc.queryForObject("""
                SELECT confirmed_qty FROM putaway_task_lines WHERE tenant_id = ? AND public_id = ?
                """, BigDecimal.class, fixture.tenantId(), lineId));
        assertEquals(3, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(1, count("SELECT COUNT(*) FROM putaway_confirmations WHERE tenant_id = ? AND request_id = ?",
                fixture.tenantId(), requestId.toString()));
    }

    @Test
    void overConfirmationIsRejectedWithoutPostingAnyInventoryMovement() {
        Receipt receipt = receive(fixture, "NONE", "5.0000", List.of());
        String taskId = createTask(fixture, receipt, "5.0000", List.of());
        String lineId = taskLineId(fixture, taskId);
        startTask(fixture, taskId);

        assertThrows(IllegalArgumentException.class, () -> putawayService.confirm(
                fixture.principal(), taskId, lineId,
                new WmsPutawayManagementService.ConfirmPutawayCommand(
                        UUID.randomUUID(), new BigDecimal("5.0001"), List.of(), null)));

        assertEquals(new BigDecimal("5.0000"), balance(fixture, fixture.receivingLocationPublicId()));
        assertEquals(BigDecimal.ZERO.setScale(4), balance(fixture, fixture.storageLocationPublicId()));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals("IN_PROGRESS", taskStatus(fixture, taskId));
    }

    @Test
    void serialTrackedPutawayMovesOnlyTheSelectedReceiptSerial() {
        Fixture serialFixture = fixture("SERIAL");
        List<String> serialNumbers = List.of("PUT-" + UUID.randomUUID(), "PUT-" + UUID.randomUUID());
        Receipt receipt = receive(serialFixture, "SERIAL", "2.0000", serialNumbers);
        List<String> serialPublicIds = jdbc.queryForList("""
                SELECT public_id FROM inventory_serials
                WHERE tenant_id = ? AND serial_number IN (?, ?)
                ORDER BY serial_number
                """, String.class, serialFixture.tenantId(), serialNumbers.get(0), serialNumbers.get(1));
        String taskId = createTask(serialFixture, receipt, "2.0000", serialPublicIds);
        String lineId = taskLineId(serialFixture, taskId);
        startTask(serialFixture, taskId);

        putawayService.confirm(serialFixture.principal(), taskId, lineId,
                new WmsPutawayManagementService.ConfirmPutawayCommand(
                        UUID.randomUUID(), BigDecimal.ONE, List.of(serialPublicIds.getFirst()), null));

        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_serials
                WHERE tenant_id = ? AND public_id = ? AND current_location_id =
                  (SELECT id FROM warehouse_locations WHERE tenant_id = ? AND public_id = ?)
                """, serialFixture.tenantId(), serialPublicIds.getFirst(),
                serialFixture.tenantId(), serialFixture.storageLocationPublicId()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_serials
                WHERE tenant_id = ? AND public_id = ? AND current_location_id =
                  (SELECT id FROM warehouse_locations WHERE tenant_id = ? AND public_id = ?)
                """, serialFixture.tenantId(), serialPublicIds.get(1),
                serialFixture.tenantId(), serialFixture.receivingLocationPublicId()));
        assertEquals("IN_PROGRESS", taskStatus(serialFixture, taskId));
    }

    private WmsAuthenticatedPrincipal independentReceivingPrincipal(Fixture fixture) {
        String userPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', 'Independent Receiving User', 'ACTIVE')
                """, userPublicId, "putaway-independent-" + UUID.randomUUID() + "@example.test");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE public_id = ?",
                Long.class, userPublicId);
        String membershipPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status, joined_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                """, membershipPublicId, fixture.tenantId(), userId);
        long membershipId = jdbc.queryForObject("SELECT id FROM tenant_memberships WHERE public_id = ?",
                Long.class, membershipPublicId);
        long roleId = jdbc.queryForObject("""
                SELECT role_id FROM membership_roles
                WHERE tenant_id = ? AND membership_id = ? ORDER BY role_id LIMIT 1
                """, Long.class, fixture.tenantId(), fixture.principal().membershipId());
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                fixture.tenantId(), membershipId, roleId);
        String tenantPublicId = jdbc.queryForObject(
                "SELECT public_id FROM tenants WHERE id = ?", String.class, fixture.tenantId());
        return new WmsAuthenticatedPrincipal(userPublicId, fixture.tenantId(), tenantPublicId,
                membershipId, membershipPublicId, Set.of());
    }

    private Receipt receive(Fixture f, String tracking, String qty, List<String> serialNumbers) {
        String receiptId = (String) inboundService.create(f.principal(),
                new WmsInboundReceivingService.ReceiptCommand(
                        f.warehousePublicId(), f.supplierPublicId(), null, f.receivingLocationPublicId(),
                        "IN-" + UUID.randomUUID().toString().substring(0, 12),
                        null, "PURCHASE", null, null)).get("public_id");
        String lineId = (String) inboundService.addLine(f.principal(), receiptId,
                new WmsInboundReceivingService.ReceiptLineCommand(
                        f.itemPublicId(), new BigDecimal(qty), null)).get("public_id");
        List<WmsInboundReceivingService.SerialReceipt> serials = serialNumbers.stream()
                .map(value -> new WmsInboundReceivingService.SerialReceipt(value, null)).toList();
        inboundService.post(independentReceivingPrincipal(f), receiptId,
                new WmsInboundReceivingService.PostReceiptCommand(UUID.randomUUID(), List.of(
                        new WmsInboundReceivingService.LineReceiptCommand(
                                lineId, new BigDecimal(qty), new BigDecimal(qty), BigDecimal.ZERO,
                                null, List.of(), serials))));
        return new Receipt(receiptId, lineId);
    }

    private String createTask(Fixture f, Receipt receipt, String qty, List<String> serialIds) {
        return (String) putawayService.create(f.principal(),
                new WmsPutawayManagementService.CreateTaskCommand(
                        receipt.receiptPublicId(), "PA-" + UUID.randomUUID().toString().substring(0, 12),
                        "NORMAL", null, null,
                        List.of(new WmsPutawayManagementService.TaskLineCommand(
                                receipt.linePublicId(), null, f.storageLocationPublicId(),
                                new BigDecimal(qty), serialIds, null)))).get("public_id");
    }

    private void startTask(Fixture f, String taskId) {
        putawayService.ready(f.principal(), taskId);
        putawayService.assign(f.principal(), taskId,
                new WmsPutawayManagementService.AssignTaskCommand(f.membershipPublicId()));
        putawayService.start(f.principal(), taskId);
    }

    private String taskLineId(Fixture f, String taskId) {
        return jdbc.queryForObject("""
                SELECT l.public_id FROM putaway_task_lines l
                JOIN putaway_tasks t ON t.id = l.putaway_task_id AND t.tenant_id = l.tenant_id
                WHERE l.tenant_id = ? AND t.public_id = ?
                """, String.class, f.tenantId(), taskId);
    }

    private String taskStatus(Fixture f, String taskId) {
        return jdbc.queryForObject("SELECT status FROM putaway_tasks WHERE tenant_id = ? AND public_id = ?",
                String.class, f.tenantId(), taskId);
    }

    private String lineStatus(Fixture f, String lineId) {
        return jdbc.queryForObject("SELECT status FROM putaway_task_lines WHERE tenant_id = ? AND public_id = ?",
                String.class, f.tenantId(), lineId);
    }

    private BigDecimal balance(Fixture f, String locationPublicId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(b.on_hand_qty), 0.0000)
                FROM inventory_balances b
                JOIN warehouse_locations l
                  ON l.id = b.location_id AND l.warehouse_id = b.warehouse_id AND l.tenant_id = b.tenant_id
                WHERE b.tenant_id = ? AND b.item_id =
                  (SELECT id FROM items WHERE tenant_id = ? AND public_id = ?)
                  AND l.public_id = ?
                """, BigDecimal.class, f.tenantId(), f.tenantId(), f.itemPublicId(), locationPublicId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Fixture fixture(String tracking) {
        String tenantPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Putaway Test Legal', 'Putaway Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, tenantPublicId, "PA_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE public_id = ?", Long.class, tenantPublicId);

        String userPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', 'Putaway Test User', 'ACTIVE')
                """, userPublicId, "putaway-" + UUID.randomUUID() + "@example.test");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE public_id = ?", Long.class, userPublicId);
        String membershipPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status, joined_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                """, membershipPublicId, tenantId, userId);
        long membershipId = jdbc.queryForObject("SELECT id FROM tenant_memberships WHERE public_id = ?",
                Long.class, membershipPublicId);

        String rolePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO roles (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                VALUES (?, ?, ?, 'Putaway Test Role', 'Integration test role', 0, 'ACTIVE')
                """, rolePublicId, tenantId, "PA_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE public_id = ?", Long.class, rolePublicId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenantId, membershipId, roleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE permission_code IN (
                  'WMS_INBOUND_RECEIPT_READ', 'WMS_INBOUND_RECEIPT_CREATE',
                  'WMS_INBOUND_RECEIPT_UPDATE', 'WMS_INBOUND_RECEIPT_POST',
                  'WMS_PUTAWAY_READ', 'WMS_PUTAWAY_CREATE', 'WMS_PUTAWAY_ASSIGN',
                  'WMS_PUTAWAY_START', 'WMS_PUTAWAY_CONFIRM'
                )
                """, roleId);

        String warehousePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, 'Putaway Test Warehouse', 'ACTIVE')
                """, warehousePublicId, tenantId, "WH_" + UUID.randomUUID().toString().substring(0, 8));
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE public_id = ?",
                Long.class, warehousePublicId);

        String receivingLocationPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type,
                   is_receivable, allow_mixed_skus, allow_mixed_lots, status)
                VALUES (?, ?, ?, ?, 'Receiving', 'RECEIVING', 1, 1, 1, 'ACTIVE')
                """, receivingLocationPublicId, tenantId, warehouseId,
                "RCV_" + UUID.randomUUID().toString().substring(0, 8));
        String storageLocationPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type,
                   allow_mixed_skus, allow_mixed_lots, putaway_sequence, status)
                VALUES (?, ?, ?, ?, 'Storage', 'STORAGE', 1, 1, 10, 'ACTIVE')
                """, storageLocationPublicId, tenantId, warehouseId,
                "STO_" + UUID.randomUUID().toString().substring(0, 8));

        String supplierPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO business_partners (public_id, tenant_id, partner_code, partner_name, status)
                VALUES (?, ?, ?, 'Putaway Test Supplier', 'ACTIVE')
                """, supplierPublicId, tenantId, "SUP_" + UUID.randomUUID().toString().substring(0, 8));
        long supplierId = jdbc.queryForObject("SELECT id FROM business_partners WHERE public_id = ?",
                Long.class, supplierPublicId);
        jdbc.update("""
                INSERT INTO business_partner_roles (tenant_id, business_partner_id, role_type)
                VALUES (?, ?, 'SUPPLIER')
                """, tenantId, supplierId);

        String itemPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO items (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method, status)
                VALUES (?, ?, ?, 'Putaway Test Item', (SELECT id FROM units_of_measure WHERE uom_code = 'EA'), ?, 'ACTIVE')
                """, itemPublicId, tenantId, "PA_" + UUID.randomUUID().toString().substring(0, 12), tracking);
        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(userPublicId, tenantId,
                tenantPublicId, membershipId, membershipPublicId, Set.of());
        return new Fixture(tenantId, warehousePublicId, receivingLocationPublicId,
                storageLocationPublicId, supplierPublicId, itemPublicId,
                membershipPublicId, principal);
    }

    private record Receipt(String receiptPublicId, String linePublicId) {}
    private record Fixture(long tenantId, String warehousePublicId, String receivingLocationPublicId,
                           String storageLocationPublicId, String supplierPublicId, String itemPublicId,
                           String membershipPublicId, WmsAuthenticatedPrincipal principal) {}

    @Test
    void tenantCannotReadForeignPutawayTaskOrItsNestedLines() {
        Fixture foreign = fixture("NONE");
        Receipt foreignReceipt = receive(foreign, "NONE", "2.0000", List.of());
        String foreignTaskId = createTask(foreign, foreignReceipt, "2.0000", List.of());

        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> createTask(fixture, foreignReceipt, "2.0000", List.of()));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> putawayService.get(fixture.principal(), foreignTaskId));
        assertEquals(1, count("SELECT COUNT(*) FROM putaway_tasks WHERE tenant_id = ? AND public_id = ?",
                foreign.tenantId(), foreignTaskId));
        assertEquals(0, count("SELECT COUNT(*) FROM putaway_tasks WHERE tenant_id = ? AND public_id = ?",
                fixture.tenantId(), foreignTaskId));
    }
}
