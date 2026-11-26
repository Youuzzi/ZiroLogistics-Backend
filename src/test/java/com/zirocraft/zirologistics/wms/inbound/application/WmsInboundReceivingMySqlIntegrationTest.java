package com.zirocraft.zirologistics.wms.inbound.application;

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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsInboundReceivingMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_inbound_receiving_test")
            .withUsername("zirowms_inbound_receiving_test")
            .withPassword("zirowms_inbound_receiving_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsInboundReceivingService receivingService;
    @Autowired private JdbcTemplate jdbc;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture("NONE");
    }

    @Test
    void receivesAcceptedStockAndRejectedQuantityWithoutAddingRejectedStock() {
        String receiptId = createReceipt(fixture);
        String lineId = addLine(fixture, receiptId, fixture.itemPublicId(), "10.0000");

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> receivingService.post(fixture.principal(), receiptId,
                        new WmsInboundReceivingService.PostReceiptCommand(UUID.randomUUID(), List.of(
                                new WmsInboundReceivingService.LineReceiptCommand(
                                        lineId, new BigDecimal("8.0000"), new BigDecimal("7.0000"),
                                        new BigDecimal("1.0000"), "Damaged outer carton", List.of(), List.of())))));
        assertEquals("DRAFT", receiptStatus(fixture, receiptId));
        receivingService.post(independentReceivingPrincipal(fixture), receiptId,
                new WmsInboundReceivingService.PostReceiptCommand(UUID.randomUUID(), List.of(
                        new WmsInboundReceivingService.LineReceiptCommand(
                                lineId, new BigDecimal("8.0000"), new BigDecimal("7.0000"),
                                new BigDecimal("1.0000"), "Damaged outer carton", List.of(), List.of()))));

        assertEquals("POSTED", receiptStatus(fixture, receiptId));
        assertEquals(new BigDecimal("7.0000"), stockBalance(fixture, fixture.itemPublicId()));
        assertEquals(new BigDecimal("8.0000"), jdbc.queryForObject(
                "SELECT received_qty FROM inbound_receipt_lines WHERE public_id = ?", BigDecimal.class, lineId));
        assertEquals(new BigDecimal("1.0000"), jdbc.queryForObject(
                "SELECT rejected_qty FROM inbound_receipt_lines WHERE public_id = ?", BigDecimal.class, lineId));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND event_type = 'INBOUND_RECEIPT_POSTED'
                """, fixture.tenantId()));
    }

    @Test
    void rejectedOnlyReceiptCompletesWithoutCreatingInventoryOrLedgerEntries() {
        String receiptId = createReceipt(fixture);
        String lineId = addLine(fixture, receiptId, fixture.itemPublicId(), "5.0000");

        receivingService.post(independentReceivingPrincipal(fixture), receiptId,
                new WmsInboundReceivingService.PostReceiptCommand(UUID.randomUUID(), List.of(
                        new WmsInboundReceivingService.LineReceiptCommand(
                                lineId, new BigDecimal("5.0000"), BigDecimal.ZERO,
                                new BigDecimal("5.0000"), "Entire delivery rejected", List.of(), List.of()))));

        assertEquals("POSTED", receiptStatus(fixture, receiptId));
        assertEquals(BigDecimal.ZERO.setScale(4), stockBalance(fixture, fixture.itemPublicId()));
        assertEquals(new BigDecimal("5.0000"), jdbc.queryForObject(
                "SELECT received_qty FROM inbound_receipt_lines WHERE tenant_id = ? AND public_id = ?",
                BigDecimal.class, fixture.tenantId(), lineId));
        assertEquals(BigDecimal.ZERO.setScale(4), jdbc.queryForObject(
                "SELECT accepted_qty FROM inbound_receipt_lines WHERE tenant_id = ? AND public_id = ?",
                BigDecimal.class, fixture.tenantId(), lineId));
        assertEquals(new BigDecimal("5.0000"), jdbc.queryForObject(
                "SELECT rejected_qty FROM inbound_receipt_lines WHERE tenant_id = ? AND public_id = ?",
                BigDecimal.class, fixture.tenantId(), lineId));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_balances WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND event_type = 'INBOUND_RECEIPT_POSTED'
                """, fixture.tenantId()));
    }

    @Test
    void receivedQuantityMismatchRollsBackReceiptFactsStockAndPostingAudit() {
        String receiptId = createReceipt(fixture);
        String lineId = addLine(fixture, receiptId, fixture.itemPublicId(), "5.0000");

        assertThrows(IllegalArgumentException.class, () -> receivingService.post(independentReceivingPrincipal(fixture), receiptId,
                new WmsInboundReceivingService.PostReceiptCommand(UUID.randomUUID(), List.of(
                        new WmsInboundReceivingService.LineReceiptCommand(
                                lineId, new BigDecimal("5.0000"), new BigDecimal("3.0000"),
                                BigDecimal.ONE, "Rejected remainder", List.of(), List.of())))));

        assertEquals("DRAFT", receiptStatus(fixture, receiptId));
        assertEquals(BigDecimal.ZERO.setScale(4), stockBalance(fixture, fixture.itemPublicId()));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM inbound_receipt_lines WHERE tenant_id = ? AND public_id = ?",
                String.class, fixture.tenantId(), lineId));
        assertEquals(BigDecimal.ZERO.setScale(4), jdbc.queryForObject(
                "SELECT received_qty FROM inbound_receipt_lines WHERE tenant_id = ? AND public_id = ?",
                BigDecimal.class, fixture.tenantId(), lineId));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND event_type = 'INBOUND_RECEIPT_POSTED'
                """, fixture.tenantId()));
    }

    @Test
    void serialTrackedReceiptRegistersAndLinksEverySerialInSameTransaction() {
        String serialItem = createItem(fixture.tenantId(), "SERIAL");
        String receiptId = createReceipt(fixture);
        String lineId = addLine(fixture, receiptId, serialItem, "2.0000");
        String first = "IN-" + UUID.randomUUID();
        String second = "IN-" + UUID.randomUUID();

        receivingService.post(independentReceivingPrincipal(fixture), receiptId,
                new WmsInboundReceivingService.PostReceiptCommand(UUID.randomUUID(), List.of(
                        new WmsInboundReceivingService.LineReceiptCommand(
                                lineId, new BigDecimal("2.0000"), new BigDecimal("2.0000"),
                                BigDecimal.ZERO, null, List.of(),
                                List.of(new WmsInboundReceivingService.SerialReceipt(first, null),
                                        new WmsInboundReceivingService.SerialReceipt(second, null))))));

        assertEquals(new BigDecimal("2.0000"), stockBalance(fixture, serialItem));
        assertEquals(2, count("""
                SELECT COUNT(*) FROM inbound_receipt_line_serials s
                JOIN inbound_receipt_lines l ON l.id = s.inbound_receipt_line_id
                WHERE l.public_id = ?
                """, lineId));
        assertEquals(2, count("""
                SELECT COUNT(*) FROM inventory_serials
                WHERE tenant_id = ? AND serial_number IN (?, ?)
                """, fixture.tenantId(), first, second));
    }

    @Test
    void duplicateSerialInputFailsWithoutChangingReceiptOrStock() {
        String serialItem = createItem(fixture.tenantId(), "SERIAL");
        String receiptId = createReceipt(fixture);
        String lineId = addLine(fixture, receiptId, serialItem, "2.0000");
        String duplicate = "DUP-" + UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> receivingService.post(independentReceivingPrincipal(fixture), receiptId,
                new WmsInboundReceivingService.PostReceiptCommand(UUID.randomUUID(), List.of(
                        new WmsInboundReceivingService.LineReceiptCommand(
                                lineId, new BigDecimal("2.0000"), new BigDecimal("2.0000"),
                                BigDecimal.ZERO, null, List.of(),
                                List.of(new WmsInboundReceivingService.SerialReceipt(duplicate, null),
                                        new WmsInboundReceivingService.SerialReceipt(duplicate, null)))))));

        assertEquals("DRAFT", receiptStatus(fixture, receiptId));
        assertEquals(BigDecimal.ZERO.setScale(4), stockBalance(fixture, serialItem));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
    }


    @Test
    void inboundReceiptsAndReferencesAreIsolatedAcrossTenants() {
        Fixture otherTenant = fixture("NONE");
        String receiptId = createReceipt(fixture);
        String ownLineId = addLine(fixture, receiptId, fixture.itemPublicId(), "5.0000");

        assertTrue(receivingService.list(fixture.principal()).stream()
                .anyMatch(row -> receiptId.equals(row.get("public_id"))));
        assertFalse(receivingService.list(otherTenant.principal()).stream()
                .anyMatch(row -> receiptId.equals(row.get("public_id"))));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> receivingService.get(otherTenant.principal(), receiptId));

        int receiptCountBefore = count(
                "SELECT COUNT(*) FROM inbound_receipts WHERE tenant_id = ?", fixture.tenantId());
        int auditCountBefore = count(
                "SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ?", fixture.tenantId());

        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> receivingService.create(fixture.principal(),
                        new WmsInboundReceivingService.ReceiptCommand(
                                otherTenant.warehousePublicId(), fixture.supplierPublicId(), null,
                                fixture.locationPublicId(), "CROSS-WH-" + UUID.randomUUID(),
                                null, "PURCHASE", null, null)));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> receivingService.create(fixture.principal(),
                        new WmsInboundReceivingService.ReceiptCommand(
                                fixture.warehousePublicId(), otherTenant.supplierPublicId(), null,
                                fixture.locationPublicId(), "CROSS-SUP-" + UUID.randomUUID(),
                                null, "PURCHASE", null, null)));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> receivingService.create(fixture.principal(),
                        new WmsInboundReceivingService.ReceiptCommand(
                                fixture.warehousePublicId(), fixture.supplierPublicId(), null,
                                otherTenant.locationPublicId(), "CROSS-LOC-" + UUID.randomUUID(),
                                null, "PURCHASE", null, null)));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> receivingService.addLine(otherTenant.principal(), receiptId,
                        new WmsInboundReceivingService.ReceiptLineCommand(
                                otherTenant.itemPublicId(), new BigDecimal("1.0000"), null)));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class,
                () -> receivingService.addLine(fixture.principal(), receiptId,
                        new WmsInboundReceivingService.ReceiptLineCommand(
                                otherTenant.itemPublicId(), new BigDecimal("1.0000"), null)));

        assertEquals(receiptCountBefore,
                count("SELECT COUNT(*) FROM inbound_receipts WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inbound_receipt_lines
                WHERE tenant_id = ? AND inbound_receipt_id =
                  (SELECT id FROM inbound_receipts WHERE tenant_id = ? AND public_id = ?)
                """, fixture.tenantId(), fixture.tenantId(), receiptId));
        assertEquals(auditCountBefore,
                count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals("DRAFT", receiptStatus(fixture, receiptId));
        assertEquals(1, count(
                "SELECT COUNT(*) FROM inbound_receipt_lines WHERE tenant_id = ? AND public_id = ?",
                fixture.tenantId(), ownLineId));
    }

    private String createReceipt(Fixture f) {
        return (String) receivingService.create(f.principal(),
                new WmsInboundReceivingService.ReceiptCommand(
                        f.warehousePublicId(), f.supplierPublicId(), null, f.locationPublicId(),
                        "IN-" + UUID.randomUUID().toString().substring(0, 12),
                        null, "PURCHASE", null, null)).get("public_id");
    }

    private String addLine(Fixture f, String receiptId, String itemPublicId, String expected) {
        return (String) receivingService.addLine(f.principal(), receiptId,
                new WmsInboundReceivingService.ReceiptLineCommand(
                        itemPublicId, new BigDecimal(expected), null)).get("public_id");
    }

    private String receiptStatus(Fixture f, String receiptId) {
        return jdbc.queryForObject("SELECT status FROM inbound_receipts WHERE tenant_id = ? AND public_id = ?",
                String.class, f.tenantId(), receiptId);
    }

    private BigDecimal stockBalance(Fixture f, String itemPublicId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand_qty), 0.0000)
                FROM inventory_balances
                WHERE tenant_id = ? AND item_id = (SELECT id FROM items WHERE tenant_id = ? AND public_id = ?)
                """, BigDecimal.class, f.tenantId(), f.tenantId(), itemPublicId);
    }

    private String createItem(long tenantId, String tracking) {
        String publicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO items (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method, status)
                VALUES (?, ?, ?, 'Inbound Test Item', (SELECT id FROM units_of_measure WHERE uom_code = 'EA'), ?, 'ACTIVE')
                """, publicId, tenantId, "IN_" + UUID.randomUUID().toString().substring(0, 12), tracking);
        return publicId;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Fixture fixture(String tracking) {
        String tenantPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Inbound Test Legal', 'Inbound Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, tenantPublicId, "IN_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE public_id = ?", Long.class, tenantPublicId);

        String userPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', 'Inbound Test User', 'ACTIVE')
                """, userPublicId, "inbound-" + UUID.randomUUID() + "@example.test");
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
                VALUES (?, ?, ?, 'Inbound Test Role', 'Integration test role', 0, 'ACTIVE')
                """, rolePublicId, tenantId, "IN_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE public_id = ?", Long.class, rolePublicId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenantId, membershipId, roleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE permission_code IN (
                  'WMS_INBOUND_RECEIPT_READ',
                  'WMS_INBOUND_RECEIPT_CREATE', 'WMS_INBOUND_RECEIPT_UPDATE',
                  'WMS_INBOUND_RECEIPT_POST'
                )
                """, roleId);

        String warehousePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, 'Inbound Test Warehouse', 'ACTIVE')
                """, warehousePublicId, tenantId, "WH_" + UUID.randomUUID().toString().substring(0, 8));
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE public_id = ?",
                Long.class, warehousePublicId);
        String locationPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type,
                   is_receivable, status)
                VALUES (?, ?, ?, ?, 'Inbound Receiving', 'RECEIVING', 1, 'ACTIVE')
                """, locationPublicId, tenantId, warehouseId,
                "RCV_" + UUID.randomUUID().toString().substring(0, 8));

        String supplierPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO business_partners (public_id, tenant_id, partner_code, partner_name, status)
                VALUES (?, ?, ?, 'Inbound Test Supplier', 'ACTIVE')
                """, supplierPublicId, tenantId, "SUP_" + UUID.randomUUID().toString().substring(0, 8));
        long supplierId = jdbc.queryForObject("SELECT id FROM business_partners WHERE public_id = ?",
                Long.class, supplierPublicId);
        jdbc.update("""
                INSERT INTO business_partner_roles (tenant_id, business_partner_id, role_type)
                VALUES (?, ?, 'SUPPLIER')
                """, tenantId, supplierId);

        String itemPublicId = createItem(tenantId, tracking);
        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(userPublicId, tenantId,
                tenantPublicId, membershipId, membershipPublicId, Set.of());
        return new Fixture(tenantId, warehousePublicId, locationPublicId, supplierPublicId,
                itemPublicId, principal);
    }

    private WmsAuthenticatedPrincipal independentReceivingPrincipal(Fixture fixture) {
        String userPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', 'Independent Receiving User', 'ACTIVE')
                """, userPublicId, "inbound-independent-" + UUID.randomUUID() + "@example.test");
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

    private record Fixture(long tenantId, String warehousePublicId, String locationPublicId,
                           String supplierPublicId, String itemPublicId,
                           WmsAuthenticatedPrincipal principal) {}
}
