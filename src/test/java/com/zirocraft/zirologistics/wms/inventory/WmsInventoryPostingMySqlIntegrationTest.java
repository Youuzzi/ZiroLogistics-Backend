package com.zirocraft.zirologistics.wms.inventory;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryQueryService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
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
import java.util.concurrent.Callable;
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
            .withUsername("wms_inventory_posting_test")
            .withPassword("wms_inventory_posting_test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsInventoryPostingService postingService;
    @Autowired private WmsInventoryQueryService queryService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void receiptIssueAndLedgerAreCommittedAsOneAuditedMovementStream() {
        Fixture f = fixture("NONE");
        post(f, "RECEIPT", leg(f, f.locationA(), null, null, "AVAILABLE", "10.0000"));
        post(f, "ISSUE", leg(f, f.locationA(), null, null, "AVAILABLE", "3.0000"));

        assertEquals(new BigDecimal("7.0000"), balance(f, f.locationA(), "AVAILABLE"));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", f.tenantId()));
        assertEquals(2, count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ? AND event_type = 'INVENTORY_MOVEMENT_POSTED'",
                f.tenantId()));
        assertEquals(7, jdbc.queryForObject("""
                SELECT available_qty FROM inventory_balances
                WHERE tenant_id = ? AND warehouse_id = ? AND location_id = ? AND item_id = ?
                  AND stock_status = 'AVAILABLE'
                """, BigDecimal.class, f.tenantId(), f.warehouseId(), f.locationAId(), f.itemId()).intValue());
    }

    @Test
    void insufficientIssueRollsBackBalanceLedgerAndAuditTogether() {
        Fixture f = fixture("NONE");
        post(f, "RECEIPT", leg(f, f.locationA(), null, null, "AVAILABLE", "5.0000"));
        UUID failedRequest = UUID.randomUUID();

        assertThrows(RuntimeException.class, () -> post(f, failedRequest, "ISSUE",
                leg(f, f.locationA(), null, null, "AVAILABLE", "6.0000")));

        assertEquals(new BigDecimal("5.0000"), balance(f, f.locationA(), "AVAILABLE"));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", f.tenantId()));
        assertEquals(1, count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ? AND event_type = 'INVENTORY_MOVEMENT_POSTED'",
                f.tenantId()));
    }

    @Test
    void duplicateRequestIdCannotApplyStockTwice() {
        Fixture f = fixture("NONE");
        UUID requestId = UUID.randomUUID();
        post(f, requestId, "RECEIPT", leg(f, f.locationA(), null, null, "AVAILABLE", "4.0000"));

        assertThrows(RuntimeException.class, () -> post(f, requestId, "RECEIPT",
                leg(f, f.locationA(), null, null, "AVAILABLE", "4.0000")));

        assertEquals(new BigDecimal("4.0000"), balance(f, f.locationA(), "AVAILABLE"));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", f.tenantId()));
    }

    @Test
    void transferDamageAndStatusChangeUseBalancedPairedLedgerEntries() {
        Fixture f = fixture("NONE");
        post(f, "RECEIPT", leg(f, f.locationA(), null, null, "AVAILABLE", "10.0000"));
        post(f, "TRANSFER",
                leg(f, f.locationA(), null, null, "AVAILABLE", "4.0000"),
                leg(f, f.locationB(), null, null, "AVAILABLE", "4.0000"));
        post(f, "DAMAGE",
                leg(f, f.locationA(), null, null, "AVAILABLE", "2.0000"),
                leg(f, f.locationA(), null, null, "DAMAGED", "2.0000"));
        post(f, "STATUS_CHANGE",
                leg(f, f.locationA(), null, null, "AVAILABLE", "1.0000"),
                leg(f, f.locationA(), null, null, "QUARANTINED", "1.0000"));
        post(f, "ADJUSTMENT_OUT", leg(f, f.locationA(), null, null, "DAMAGED", "1.0000"));

        assertEquals(new BigDecimal("3.0000"), balance(f, f.locationA(), "AVAILABLE"));
        assertEquals(new BigDecimal("4.0000"), balance(f, f.locationB(), "AVAILABLE"));
        assertEquals(new BigDecimal("1.0000"), balance(f, f.locationA(), "DAMAGED"));
        assertEquals(new BigDecimal("1.0000"), balance(f, f.locationA(), "QUARANTINED"));
        assertEquals(8, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", f.tenantId()));
    }

    @Test
    void serialReceiptIssueReturnAndInspectionUpdateSerialAndStockAtomically() {
        Fixture f = fixture("SERIAL");
        post(f, "RECEIPT", leg(f, f.locationA(), null, "SERIAL-001", "AVAILABLE", "1.0000"));
        String serialPublicId = jdbc.queryForObject("""
                SELECT public_id FROM inventory_serials WHERE tenant_id = ? AND serial_number = 'SERIAL-001'
                """, String.class, f.tenantId());

        post(f, "ISSUE", leg(f, f.locationA(), serialPublicId, null, "AVAILABLE", "1.0000"));
        assertEquals("SHIPPED", serialStatus(f, serialPublicId));

        post(f, "RETURN", leg(f, f.locationA(), serialPublicId, null, "QUARANTINED", "1.0000"));
        assertEquals("RETURNED", serialStatus(f, serialPublicId));
        assertEquals(new BigDecimal("1.0000"), balance(f, f.locationA(), "QUARANTINED"));

        post(f, "STATUS_CHANGE",
                leg(f, f.locationA(), serialPublicId, null, "QUARANTINED", "1.0000"),
                leg(f, f.locationA(), serialPublicId, null, "AVAILABLE", "1.0000"));

        assertEquals("AVAILABLE", serialStatus(f, serialPublicId));
        assertEquals(new BigDecimal("1.0000"), balance(f, f.locationA(), "AVAILABLE"));
        assertEquals(5, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", f.tenantId()));
    }

    @Test
    void concurrentIssuesCannotDriveAvailableStockBelowZero() throws Exception {
        Fixture f = fixture("NONE");
        post(f, "RECEIPT", leg(f, f.locationA(), null, null, "AVAILABLE", "10.0000"));
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> issue = () -> {
                try {
                    post(f, UUID.randomUUID(), "ISSUE",
                            leg(f, f.locationA(), null, null, "AVAILABLE", "7.0000"));
                    return true;
                } catch (RuntimeException rejected) {
                    return false;
                }
            };
            var first = pool.submit(issue);
            var second = pool.submit(issue);
            int successes = (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(new BigDecimal("3.0000"), balance(f, f.locationA(), "AVAILABLE"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void readApisAreTenantScopedAndBounded() {
        Fixture a = fixture("NONE");
        Fixture b = fixture("NONE");
        post(a, "RECEIPT", leg(a, a.locationA(), null, null, "AVAILABLE", "2.0000"));
        post(b, "RECEIPT", leg(b, b.locationA(), null, null, "AVAILABLE", "9.0000"));

        Map<String, Object> balances = queryService.balances(principal(a), null, null, null, null, 0, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> balanceRows = (List<Map<String, Object>>) balances.get("content");
        assertEquals(1, balanceRows.size());
        assertEquals(new BigDecimal("2.0000"), balanceRows.getFirst().get("on_hand_qty"));

        Map<String, Object> ledger = queryService.ledger(principal(a), null, null, null, null, 0, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ledgerRows = (List<Map<String, Object>>) ledger.get("content");
        assertEquals(1, ledgerRows.size());
        assertFalse(ledgerRows.getFirst().containsKey("actor_membership_id"));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.balances(principal(a), null, null, null, "INVALID", 0, 20));
        assertThrows(IllegalArgumentException.class,
                () -> queryService.ledger(principal(a), null, null, null, null, 0, 201));
    }

    private Map<String, Object> post(Fixture f, String movement, WmsInventoryPostingService.PostingLeg... legs) {
        return post(f, UUID.randomUUID(), movement, legs);
    }

    private Map<String, Object> post(Fixture f, UUID requestId, String movement,
                                     WmsInventoryPostingService.PostingLeg... legs) {
        return postingService.post(principal(f), new WmsInventoryPostingService.PostingCommand(
                requestId, movement, List.of(legs), "Integration test movement"));
    }

    private WmsInventoryPostingService.PostingLeg leg(Fixture f, String locationPublicId,
            String serialPublicId, String serialNumber, String stockStatus, String quantity) {
        return new WmsInventoryPostingService.PostingLeg(f.warehousePublicId(), locationPublicId,
                f.itemPublicId(), null, serialPublicId, serialNumber, stockStatus, new BigDecimal(quantity));
    }

    private WmsAuthenticatedPrincipal principal(Fixture f) {
        return new WmsAuthenticatedPrincipal(f.userPublicId(), f.tenantId(), f.tenantPublicId(),
                f.membershipId(), f.membershipPublicId(), Set.of(
                "WMS_INVENTORY_POST", "WMS_INVENTORY_READ", "WMS_INVENTORY_LEDGER_READ"));
    }

    private Fixture fixture(String trackingMethod) {
        String tenantPublicId = uuid();
        String userPublicId = uuid();
        String membershipPublicId = uuid();
        String warehousePublicId = uuid();
        String locationAPublicId = uuid();
        String locationBPublicId = uuid();
        String itemPublicId = uuid();
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Inventory Test Legal', 'Inventory Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, tenantPublicId, "INV_" + tenantPublicId.substring(0, 8));
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE public_id = ?", Long.class, tenantPublicId);
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-password-hash', 'Inventory Test User', 'ACTIVE')
                """, userPublicId, "inventory." + userPublicId + "@example.test");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE public_id = ?", Long.class, userPublicId);
        jdbc.update("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status, joined_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                """, membershipPublicId, tenantId, userId);
        long membershipId = jdbc.queryForObject("SELECT id FROM tenant_memberships WHERE public_id = ?",
                Long.class, membershipPublicId);
        String rolePublicId = uuid();
        jdbc.update("""
                INSERT INTO roles (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                VALUES (?, ?, 'INVENTORY_TEST_OWNER', 'Inventory Test Owner', 'Integration test role', 0, 'ACTIVE')
                """, rolePublicId, tenantId);
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE public_id = ?", Long.class, rolePublicId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenantId, membershipId, roleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE permission_code IN ('WMS_INVENTORY_POST', 'WMS_INVENTORY_READ', 'WMS_INVENTORY_LEDGER_READ')
                """, roleId);
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name)
                VALUES (?, ?, 'MAIN', 'Main Warehouse')
                """, warehousePublicId, tenantId);
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE public_id = ?",
                Long.class, warehousePublicId);
        jdbc.update("""
                INSERT INTO warehouse_locations (public_id, tenant_id, warehouse_id, location_code,
                    location_name, location_type, is_pickable, is_receivable)
                VALUES (?, ?, ?, 'BIN-A', 'Bin A', 'BIN', 1, 1),
                       (?, ?, ?, 'BIN-B', 'Bin B', 'BIN', 1, 1)
                """, locationAPublicId, tenantId, warehouseId, locationBPublicId, tenantId, warehouseId);
        long locationAId = jdbc.queryForObject("SELECT id FROM warehouse_locations WHERE public_id = ?",
                Long.class, locationAPublicId);
        jdbc.update("""
                INSERT INTO items (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method,
                    is_stock_item, is_purchasable, is_sellable)
                SELECT ?, ?, ?, 'Integration Item', id, ?, 1, 1, 1
                FROM units_of_measure WHERE uom_code = 'EA'
                """, itemPublicId, tenantId, "SKU-" + itemPublicId.substring(0, 8), trackingMethod);
        long itemId = jdbc.queryForObject("SELECT id FROM items WHERE public_id = ?", Long.class, itemPublicId);
        return new Fixture(tenantId, tenantPublicId, userId, userPublicId, membershipId, membershipPublicId,
                warehouseId, warehousePublicId, locationAId, locationAPublicId, locationBPublicId, itemId, itemPublicId);
    }

    private BigDecimal balance(Fixture f, String locationPublicId, String status) {
        return jdbc.queryForObject("""
                SELECT b.on_hand_qty FROM inventory_balances b
                JOIN warehouse_locations l ON l.id = b.location_id
                WHERE b.tenant_id = ? AND b.item_id = ? AND l.public_id = ? AND b.stock_status = ?
                """, BigDecimal.class, f.tenantId(), f.itemId(), locationPublicId, status);
    }

    private String serialStatus(Fixture f, String serialPublicId) {
        return jdbc.queryForObject("SELECT status FROM inventory_serials WHERE tenant_id = ? AND public_id = ?",
                String.class, f.tenantId(), serialPublicId);
    }

    private int count(String sql, long tenantId) {
        return jdbc.queryForObject(sql, Integer.class, tenantId);
    }

    private static String uuid() { return UUID.randomUUID().toString(); }

    private record Fixture(long tenantId, String tenantPublicId, long userId, String userPublicId,
                           long membershipId, String membershipPublicId, long warehouseId,
                           String warehousePublicId, long locationAId,
                           String locationA, String locationB, long itemId, String itemPublicId) {

    }
}
