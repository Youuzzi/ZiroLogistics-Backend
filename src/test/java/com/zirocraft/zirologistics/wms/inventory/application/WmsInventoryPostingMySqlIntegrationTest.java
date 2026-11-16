package com.zirocraft.zirologistics.wms.inventory.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsInventoryPostingMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_inventory_posting_test")
            .withUsername("zirowms_inventory_posting_test")
            .withPassword("zirowms_inventory_posting_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsInventoryPostingService postingService;
    @Autowired private JdbcTemplate jdbc;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture("NONE");
    }

    @Test
    void postsActualQuantitiesAndRejectsDuplicateRequestWithoutDoublePosting() {
        UUID requestId = UUID.randomUUID();
        post(fixture, requestId, "RECEIPT", leg(fixture, null, null, "AVAILABLE", "10.2500"));
        assertEquals(new BigDecimal("10.2500"), balance(fixture));

        UUID issueId = UUID.randomUUID();
        post(fixture, issueId, "ISSUE", leg(fixture, null, null, "AVAILABLE", "3.1250"));
        assertEquals(new BigDecimal("7.1250"), balance(fixture));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));

        assertThrows(RuntimeException.class, () -> post(fixture, issueId, "ISSUE",
                leg(fixture, null, null, "AVAILABLE", "3.1250")));
        assertEquals(new BigDecimal("7.1250"), balance(fixture));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
    }

    @Test
    void rollsBackAllPairedBalanceAndLedgerChangesWhenSecondEntryConflicts() {
        String secondLocation = createLocation(fixture);
        post(fixture, UUID.randomUUID(), "RECEIPT", leg(fixture, null, null, "AVAILABLE", "5.0000"));
        UUID requestId = UUID.randomUUID();
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE public_id = ?",
                Long.class, fixture.warehousePublicId());
        long itemId = jdbc.queryForObject("SELECT id FROM items WHERE public_id = ?",
                Long.class, fixture.itemPublicId());
        long membershipId = fixture.membershipId();
        long destinationLocationId = jdbc.queryForObject("SELECT id FROM warehouse_locations WHERE public_id = ?",
                Long.class, secondLocation);
        jdbc.update("""
                INSERT INTO inventory_ledger
                  (public_id, tenant_id, movement_group_id, request_id, entry_no, movement_type,
                   warehouse_id, location_id, item_id, stock_status, quantity_delta,
                   balance_before_qty, balance_after_qty, actor_membership_id)
                VALUES (?, ?, ?, ?, 2, 'TRANSFER', ?, ?, ?, 'AVAILABLE', 1.0000, 0.0000, 1.0000, ?)
                """, UUID.randomUUID().toString(), fixture.tenantId(), UUID.randomUUID().toString(),
                requestId.toString(), warehouseId, destinationLocationId, itemId, membershipId);

        WmsInventoryPostingService.PostingLeg source = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("2.0000"));
        WmsInventoryPostingService.PostingLeg destination = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), secondLocation, fixture.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("2.0000"));
        assertThrows(RuntimeException.class, () -> postingService.post(fixture.principal(),
                new WmsInventoryPostingService.PostingCommand(requestId, "TRANSFER", List.of(source, destination), null)));

        assertEquals(new BigDecimal("5.0000"), bucketBalance(fixture, fixture.locationPublicId(), "AVAILABLE"));
        assertEquals(new BigDecimal("0.0000"), bucketBalance(fixture, secondLocation, "AVAILABLE"));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ? AND request_id = ?",
                fixture.tenantId(), requestId.toString()));
        assertEquals(1, count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ? AND event_type = 'INVENTORY_MOVEMENT_POSTED'",
                fixture.tenantId()));
    }
    @Test
    void serialReceiptIssueAndReturnKeepSerialStateAndStockAtomic() {
        Fixture serialFixture = fixture("SERIAL");
        String serialNumber = "SN-" + UUID.randomUUID();
        post(serialFixture, UUID.randomUUID(), "RECEIPT",
                leg(serialFixture, null, serialNumber, "AVAILABLE", "1.0000"));
        String serialPublicId = jdbc.queryForObject(
                "SELECT public_id FROM inventory_serials WHERE tenant_id = ? AND serial_number = ?",
                String.class, serialFixture.tenantId(), serialNumber);
        assertEquals("AVAILABLE", serialStatus(serialFixture, serialPublicId));

        post(serialFixture, UUID.randomUUID(), "ISSUE",
                leg(serialFixture, serialPublicId, null, "QUARANTINED", "1.0000"));
        assertEquals("SHIPPED", serialStatus(serialFixture, serialPublicId));
        assertEquals(BigDecimal.ZERO.setScale(4), balance(serialFixture));

        post(serialFixture, UUID.randomUUID(), "RETURN",
                leg(serialFixture, serialPublicId, null, "AVAILABLE", "1.0000"));
        assertEquals("RETURNED", serialStatus(serialFixture, serialPublicId));
        assertEquals(BigDecimal.ONE.setScale(4), balance(serialFixture));

        WmsInventoryPostingService.PostingLeg quarantineSource = new WmsInventoryPostingService.PostingLeg(
                serialFixture.warehousePublicId(), serialFixture.locationPublicId(), serialFixture.itemPublicId(),
                null, serialPublicId, null, "QUARANTINED", new BigDecimal("1.0000"));
        WmsInventoryPostingService.PostingLeg availableDestination = new WmsInventoryPostingService.PostingLeg(
                serialFixture.warehousePublicId(), serialFixture.locationPublicId(), serialFixture.itemPublicId(),
                null, serialPublicId, null, "AVAILABLE", new BigDecimal("1.0000"));
        postingService.post(serialFixture.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "STATUS_CHANGE", List.of(quarantineSource, availableDestination), null));
        assertEquals("AVAILABLE", serialStatus(serialFixture, serialPublicId));
        assertEquals(BigDecimal.ONE.setScale(4), balance(serialFixture));

        post(serialFixture, UUID.randomUUID(), "ADJUSTMENT_OUT",
                leg(serialFixture, serialPublicId, null, "AVAILABLE", "1.0000"));
        assertEquals("LOST", serialStatus(serialFixture, serialPublicId));
        assertEquals(BigDecimal.ZERO.setScale(4), balance(serialFixture));

        post(serialFixture, UUID.randomUUID(), "ADJUSTMENT_IN",
                leg(serialFixture, serialPublicId, null, "AVAILABLE", "1.0000"));
        assertEquals("AVAILABLE", serialStatus(serialFixture, serialPublicId));
        assertEquals(BigDecimal.ONE.setScale(4), balance(serialFixture));
    }

    @Test
    void batchesMultipleSerialsIntoOneBalanceBucketWithSequentialLedgerSnapshots() {
        Fixture serialFixture = fixture("SERIAL");
        UUID requestId = UUID.randomUUID();
        WmsInventoryPostingService.PostingLeg first = leg(serialFixture, null, "SN-BATCH-1", "AVAILABLE", "1.0000");
        WmsInventoryPostingService.PostingLeg second = leg(serialFixture, null, "SN-BATCH-2", "AVAILABLE", "1.0000");
        postingService.post(serialFixture.principal(), new WmsInventoryPostingService.PostingCommand(
                requestId, "RECEIPT", List.of(first, second), null));

        assertEquals(new BigDecimal("2.0000"), balance(serialFixture));
        List<Map<String, Object>> ledger = jdbc.queryForList("""
                SELECT balance_before_qty, balance_after_qty FROM inventory_ledger
                WHERE tenant_id = ? AND request_id = ? ORDER BY entry_no
                """, serialFixture.tenantId(), requestId.toString());
        assertEquals(2, ledger.size());
        assertEquals(new BigDecimal("0.0000"), ledger.get(0).get("balance_before_qty"));
        assertEquals(new BigDecimal("1.0000"), ledger.get(0).get("balance_after_qty"));
        assertEquals(new BigDecimal("1.0000"), ledger.get(1).get("balance_before_qty"));
        assertEquals(new BigDecimal("2.0000"), ledger.get(1).get("balance_after_qty"));
    }
    @Test
    void tenantLockSerializesCompetingIssuesSoStockCannotBecomeNegative() throws Exception {
        post(fixture, UUID.randomUUID(), "RECEIPT", leg(fixture, null, null, "AVAILABLE", "5.0000"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CompletableFuture<Boolean> a = CompletableFuture.supplyAsync(() -> issueSucceeds(fixture, first));
        CompletableFuture<Boolean> b = CompletableFuture.supplyAsync(() -> issueSucceeds(fixture, second));
        boolean firstSucceeded = a.get();
        boolean secondSucceeded = b.get();
        assertNotEquals(firstSucceeded, secondSucceeded);
        assertEquals(new BigDecimal("1.0000"), balance(fixture));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
    }

    @Test
    void rejectsCrossTenantWarehouseReference() {
        Fixture otherTenant = fixture("NONE");
        WmsInventoryPostingService.PostingLeg crossTenantLeg = new WmsInventoryPostingService.PostingLeg(
                otherTenant.warehousePublicId(), otherTenant.locationPublicId(), fixture.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("1.0000"));
        assertThrows(IllegalArgumentException.class, () -> postingService.post(fixture.principal(),
                new WmsInventoryPostingService.PostingCommand(UUID.randomUUID(), "RECEIPT",
                        List.of(crossTenantLeg), null)));
        assertEquals(BigDecimal.ZERO.setScale(4), balance(fixture));
    }

    @Test
    void transferDamageAndStatusChangeMoveExactQuantitiesBetweenBuckets() {
        post(fixture, UUID.randomUUID(), "RECEIPT", leg(fixture, null, null, "AVAILABLE", "5.0000"));
        String secondLocation = createLocation(fixture);

        WmsInventoryPostingService.PostingLeg transferSource = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("2.0000"));
        WmsInventoryPostingService.PostingLeg transferDestination = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), secondLocation, fixture.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("2.0000"));
        postingService.post(fixture.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "TRANSFER", List.of(transferSource, transferDestination), null));
        assertEquals(new BigDecimal("3.0000"), bucketBalance(fixture, fixture.locationPublicId(), "AVAILABLE"));
        assertEquals(new BigDecimal("2.0000"), bucketBalance(fixture, secondLocation, "AVAILABLE"));

        WmsInventoryPostingService.PostingLeg damageSource = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), secondLocation, fixture.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("1.0000"));
        WmsInventoryPostingService.PostingLeg damageDestination = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), secondLocation, fixture.itemPublicId(),
                null, null, null, "DAMAGED", new BigDecimal("1.0000"));
        postingService.post(fixture.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "DAMAGE", List.of(damageSource, damageDestination), null));
        assertEquals(new BigDecimal("1.0000"), bucketBalance(fixture, secondLocation, "AVAILABLE"));
        assertEquals(new BigDecimal("1.0000"), bucketBalance(fixture, secondLocation, "DAMAGED"));

        WmsInventoryPostingService.PostingLeg statusSource = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), secondLocation, fixture.itemPublicId(),
                null, null, null, "DAMAGED", new BigDecimal("1.0000"));
        WmsInventoryPostingService.PostingLeg statusDestination = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), secondLocation, fixture.itemPublicId(),
                null, null, null, "QUARANTINED", new BigDecimal("1.0000"));
        postingService.post(fixture.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "STATUS_CHANGE", List.of(statusSource, statusDestination), null));
        assertEquals(new BigDecimal("0.0000"), bucketBalance(fixture, secondLocation, "DAMAGED"));
        assertEquals(new BigDecimal("1.0000"), bucketBalance(fixture, secondLocation, "QUARANTINED"));
        assertEquals(7, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
    }
    private boolean issueSucceeds(Fixture f, UUID requestId) {
        try {
            post(f, requestId, "ISSUE", leg(f, null, null, "AVAILABLE", "4.0000"));
            return true;
        } catch (RuntimeException expected) {
            return false;
        }
    }

    private void post(Fixture f, UUID requestId, String movement, WmsInventoryPostingService.PostingLeg leg) {
        postingService.post(f.principal(), new WmsInventoryPostingService.PostingCommand(
                requestId, movement, List.of(leg), "integration test"));
    }

    private static WmsInventoryPostingService.PostingLeg leg(Fixture f, String serialPublicId,
                                                              String serialNumber, String status, String quantity) {
        return new WmsInventoryPostingService.PostingLeg(f.warehousePublicId(), f.locationPublicId(),
                f.itemPublicId(), null, serialPublicId, serialNumber, status, new BigDecimal(quantity));
    }

    private BigDecimal balance(Fixture f) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand_qty), 0.0000) FROM inventory_balances
                WHERE tenant_id = ? AND item_id = (SELECT id FROM items WHERE public_id = ?)
                """, BigDecimal.class, f.tenantId(), f.itemPublicId());
    }

    private String createLocation(Fixture f) {
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE public_id = ?",
                Long.class, f.warehousePublicId());
        String publicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouse_locations (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, 'Inventory Transfer Bin', 'BIN', 'ACTIVE')
                """, publicId, f.tenantId(), warehouseId, "BIN_" + UUID.randomUUID().toString().substring(0, 8));
        return publicId;
    }

    private BigDecimal bucketBalance(Fixture f, String locationPublicId, String stockStatus) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(b.on_hand_qty), 0.0000)
                FROM inventory_balances b
                JOIN warehouse_locations l ON l.id = b.location_id AND l.tenant_id = b.tenant_id
                JOIN items i ON i.id = b.item_id AND i.tenant_id = b.tenant_id
                WHERE b.tenant_id = ? AND l.public_id = ? AND i.public_id = ? AND b.stock_status = ?
                """, BigDecimal.class, f.tenantId(), locationPublicId, f.itemPublicId(), stockStatus);
    }
    private String serialStatus(Fixture f, String serialPublicId) {
        return jdbc.queryForObject("SELECT status FROM inventory_serials WHERE tenant_id = ? AND public_id = ?",
                String.class, f.tenantId(), serialPublicId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Fixture fixture(String trackingMethod) {
        String tenantPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Inventory Test Legal', 'Inventory Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, tenantPublicId, "INV_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE public_id = ?", Long.class, tenantPublicId);

        String userPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', 'Inventory Test User', 'ACTIVE')
                """, userPublicId, "inventory-" + UUID.randomUUID() + "@example.test");
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
                VALUES (?, ?, ?, 'Inventory Test Role', 'Integration test role', 0, 'ACTIVE')
                """, rolePublicId, tenantId, "INV_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE public_id = ?", Long.class, rolePublicId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenantId, membershipId, roleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE permission_code = 'WMS_INVENTORY_POST'
                """, roleId);

        String warehousePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, 'Inventory Test Warehouse', 'ACTIVE')
                """, warehousePublicId, tenantId, "WH_" + UUID.randomUUID().toString().substring(0, 8));
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE public_id = ?", Long.class, warehousePublicId);

        String locationPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouse_locations (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, 'Inventory Test Bin', 'BIN', 'ACTIVE')
                """, locationPublicId, tenantId, warehouseId, "BIN_" + UUID.randomUUID().toString().substring(0, 8));

        String itemPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO items (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method, status)
                VALUES (?, ?, ?, 'Inventory Test Item',
                        (SELECT id FROM units_of_measure WHERE uom_code = 'EA'), ?, 'ACTIVE')
                """, itemPublicId, tenantId, "SKU_" + UUID.randomUUID().toString().substring(0, 8), trackingMethod);

        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(userPublicId, tenantId,
                jdbc.queryForObject("SELECT public_id FROM tenants WHERE id = ?", String.class, tenantId),
                membershipId, membershipPublicId, Set.of("WMS_INVENTORY_POST"));
        return new Fixture(tenantId, tenantPublicId, userPublicId, membershipId, membershipPublicId,
                warehousePublicId, locationPublicId, itemPublicId, principal);
    }

    private record Fixture(long tenantId, String tenantPublicId, String userPublicId, long membershipId,
                           String membershipPublicId, String warehousePublicId, String locationPublicId,
                           String itemPublicId, WmsAuthenticatedPrincipal principal) {}
}