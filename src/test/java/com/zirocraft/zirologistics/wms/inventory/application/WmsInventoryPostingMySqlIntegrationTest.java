package com.zirocraft.zirologistics.wms.inventory.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProvisioningResult;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProvisioningService;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.config.WmsTenantProvisioningProperties;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    @Autowired private WmsTenantProvisioningService provisioningService;
    @Autowired private WmsTenantProvisioningProperties properties;
    @Autowired private UserRepository userRepository;
    @Autowired private WmsMembershipAccessService membershipAccessService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void postsReceiptAndIssueAtomicallyAndRollsBackWhenAuditFails() {
        Fixture fixture = fixture();
        String receiptRequest = UUID.randomUUID().toString();
        Map<String, Object> receipt = postingService.post(fixture.principal(), command(
                receiptRequest, "RECEIPT", fixture, "10.5000"));

        assertEquals("POSTED", receipt.get("status"));
        assertEquals(1, receipt.get("entryCount"));
        assertBalance(fixture, "10.5000");
        assertEquals(1, countLedger(fixture));
        assertEquals(1, countAudit(fixture));

        assertThrows(IllegalStateException.class, () -> postingService.post(
                fixture.principal(), command(receiptRequest, "RECEIPT", fixture, "10.5000")));
        assertBalance(fixture, "10.5000");
        assertEquals(1, countLedger(fixture));

        postingService.post(fixture.principal(), command(
                UUID.randomUUID().toString(), "ISSUE", fixture, "4.0000"));
        assertBalance(fixture, "6.5000");
        assertEquals(2, countLedger(fixture));
        assertEquals(2, countAudit(fixture));

        assertThrows(IllegalStateException.class, () -> postingService.post(
                fixture.principal(), command(UUID.randomUUID().toString(), "ISSUE", fixture, "7.0000")));
        assertBalance(fixture, "6.5000");
        assertEquals(2, countLedger(fixture));
        assertEquals(2, countAudit(fixture));

        jdbc.execute("""
                CREATE TRIGGER trg_test_reject_inventory_post_audit
                BEFORE INSERT ON tenant_audit_events
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced inventory audit failure'
                """);
        try {
            assertThrows(RuntimeException.class, () -> postingService.post(
                    fixture.principal(), command(UUID.randomUUID().toString(), "RECEIPT", fixture, "2.0000")));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_test_reject_inventory_post_audit");
        }
        assertBalance(fixture, "6.5000");
        assertEquals(2, countLedger(fixture), "Ledger insert must roll back when audit fails");
        assertEquals(2, countAudit(fixture), "Failed posting must not leave an audit event");
    }

    @Test
    void concurrentIssuesCannotOversellTheSameInventoryBucket() throws Exception {
        Fixture fixture = fixture();
        postingService.post(fixture.principal(), command(
                UUID.randomUUID().toString(), "RECEIPT", fixture, "10.0000"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> issueAfterBarrier(fixture, ready, start));
            var second = executor.submit(() -> issueAfterBarrier(fixture, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both posting attempts should be ready");
            start.countDown();
            boolean firstSucceeded = first.get(15, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(15, TimeUnit.SECONDS);

            assertEquals(1, (firstSucceeded ? 1 : 0) + (secondSucceeded ? 1 : 0),
                    "Exactly one issue of seven units may succeed from ten units");
            assertBalance(fixture, "3.0000");
            assertEquals(2, countLedger(fixture));
            assertEquals(2, countAudit(fixture));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private boolean issueAfterBarrier(
            Fixture fixture, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to start inventory posting");
        }
        try {
            postingService.post(fixture.principal(), command(
                    UUID.randomUUID().toString(), "ISSUE", fixture, "7.0000"));
            return true;
        } catch (IllegalStateException expectedInsufficientStock) {
            return false;
        }
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        UserEntity owner = new UserEntity();
        owner.setEmail("inventory-post-" + suffix + "@example.test");
        owner.setPasswordHash("$argon2id$integration-test");
        owner.setFullName("Inventory Posting Owner");
        owner.setStatus(UserStatus.ACTIVE);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        owner = userRepository.saveAndFlush(owner);

        configure("WMS_POST_" + suffix.substring(0, 8).toUpperCase(),
                owner.getEmail());
        WmsTenantProvisioningResult provisioned = provisioningService.provision(properties);
        WmsMembershipAccess access = membershipAccessService.resolve(
                owner.getPublicId(), provisioned.ownerMembershipPublicId());
        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                access.userPublicId(), access.tenantId(), access.tenantPublicId(),
                access.membershipId(), access.membershipPublicId(), access.permissionCodes());

        String warehouse = UUID.randomUUID().toString();
        String location = UUID.randomUUID().toString();
        String item = UUID.randomUUID().toString();
        Long uomId = jdbc.queryForObject(
                "SELECT id FROM units_of_measure WHERE uom_code = 'EA'", Long.class);
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name)
                VALUES (?, ?, ?, 'Posting Test Warehouse')
                """, warehouse, access.tenantId(), "WH-" + suffix.substring(0, 8).toUpperCase());
        Long warehouseId = jdbc.queryForObject(
                "SELECT id FROM warehouses WHERE public_id = ?", Long.class, warehouse);
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type,
                   is_receivable, is_pickable)
                VALUES (?, ?, ?, 'STORAGE-01', 'Storage 01', 'STORAGE', 1, 1)
                """, location, access.tenantId(), warehouseId);
        jdbc.update("""
                INSERT INTO items
                  (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method, is_stock_item)
                VALUES (?, ?, ?, 'Posting Test Item', ?, 'NONE', 1)
                """, item, access.tenantId(), "SKU-" + suffix.substring(0, 8).toUpperCase(), uomId);
        return new Fixture(principal, access.tenantId(), warehouse, location, item);
    }

    private void configure(String tenantCode, String ownerEmail) {
        properties.setEnabled(true);
        properties.setTenantCode(tenantCode);
        properties.setLegalName("Inventory Posting Test Legal Entity");
        properties.setDisplayName("Inventory Posting Test");
        properties.setTimezone("Asia/Jakarta");
        properties.setCurrency("IDR");
        properties.setOwnerEmail(ownerEmail);
        properties.setOwnerFullName("Inventory Posting Owner");
        properties.setOperationRef(UUID.randomUUID().toString());
        properties.setOperatorReference("operator-console:integration-test");
        properties.setReason("Disposable MySQL integration test provisioning");
    }

    private static WmsInventoryPostingService.PostingCommand command(
            String requestId, String movement, Fixture fixture, String quantity) {
        return new WmsInventoryPostingService.PostingCommand(
                requestId, movement,
                List.of(new WmsInventoryPostingService.LegCommand(
                        fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                        null, "AVAILABLE", new BigDecimal(quantity), null)),
                null, null, "inventory posting integration test");
    }

    private void assertBalance(Fixture fixture, String expected) {
        BigDecimal onHand = jdbc.queryForObject("""
                SELECT on_hand_qty FROM inventory_balances
                WHERE tenant_id = ? AND warehouse_id = (
                  SELECT id FROM warehouses WHERE public_id = ?
                ) AND location_id = (
                  SELECT id FROM warehouse_locations WHERE public_id = ?
                ) AND item_id = (SELECT id FROM items WHERE public_id = ?)
                  AND lot_id IS NULL AND stock_status = 'AVAILABLE'
                """, BigDecimal.class, fixture.tenantId(), fixture.warehousePublicId(),
                fixture.locationPublicId(), fixture.itemPublicId());
        assertEquals(0, new BigDecimal(expected).compareTo(onHand));
        BigDecimal available = jdbc.queryForObject("""
                SELECT available_qty FROM inventory_balances
                WHERE tenant_id = ? AND warehouse_id = (
                  SELECT id FROM warehouses WHERE public_id = ?
                ) AND location_id = (
                  SELECT id FROM warehouse_locations WHERE public_id = ?
                ) AND item_id = (SELECT id FROM items WHERE public_id = ?)
                  AND lot_id IS NULL AND stock_status = 'AVAILABLE'
                """, BigDecimal.class, fixture.tenantId(), fixture.warehousePublicId(),
                fixture.locationPublicId(), fixture.itemPublicId());
        assertEquals(0, new BigDecimal(expected).compareTo(available));
    }

    private int countLedger(Fixture fixture) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", Integer.class, fixture.tenantId());
    }

    private int countAudit(Fixture fixture) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND event_type = 'INVENTORY_MOVEMENT_POSTED'
                """, Integer.class, fixture.tenantId());
    }

    private record Fixture(
            WmsAuthenticatedPrincipal principal, long tenantId,
            String warehousePublicId, String locationPublicId, String itemPublicId) {}
}
