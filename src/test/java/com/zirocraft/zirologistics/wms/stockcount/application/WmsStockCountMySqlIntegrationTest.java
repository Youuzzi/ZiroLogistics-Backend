package com.zirocraft.zirologistics.wms.stockcount.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsStockCountMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_stock_count_test")
            .withUsername("zirowms_stock_count_test")
            .withPassword("zirowms_stock_count_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsStockCountManagementService stockCountService;
    @Autowired private WmsInventoryPostingService postingService;
    @Autowired private JdbcTemplate jdbc;

    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture();
    }

    @Test
    void startCapturesOnHandSnapshotFreezesLocationAndCancellationUnfreezesIt() {
        receive(fixture, "12.5000");
        String countPublicId = createAndPlan(fixture);

        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        assertEquals("IN_PROGRESS", started.get("status"));
        assertEquals(Boolean.TRUE, started.get("freezeStock"));
        assertEquals(new BigDecimal("12.5000"), jdbc.queryForObject("""
                SELECT expected_qty FROM stock_count_lines l
                JOIN stock_count_scopes s ON s.id = l.stock_count_scope_id AND s.tenant_id = l.tenant_id
                JOIN stock_counts c ON c.id = s.stock_count_id AND c.tenant_id = s.tenant_id
                WHERE c.tenant_id = ? AND c.public_id = ?
                """, BigDecimal.class, fixture.tenantId(), countPublicId));

        long ledgerBefore = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId());
        assertThrows(IllegalStateException.class, () -> receive(fixture, "1.0000"));
        assertEquals(ledgerBefore, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(new BigDecimal("12.5000"), onHand(fixture));

        Map<String, Object> cancelled = stockCountService.cancel(fixture.principal(), countPublicId);
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals(Boolean.FALSE, cancelled.get("freezeStock"));
        assertEquals("CANCELLED", jdbc.queryForObject("""
                SELECT scope_status FROM stock_count_scopes s
                JOIN stock_counts c ON c.id = s.stock_count_id AND c.tenant_id = s.tenant_id
                WHERE c.tenant_id = ? AND c.public_id = ?
                """, String.class, fixture.tenantId(), countPublicId));

        receive(fixture, "1.0000");
        assertEquals(new BigDecimal("13.5000"), onHand(fixture));
    }

    @Test
    void blindCountReadDoesNotExposeExpectedQuantityToCounter() {
        receive(fixture, "3.0000");
        String countPublicId = createAndPlan(fixture);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) started.get("lines");
        assertEquals(1, lines.size());
        assertFalse(lines.getFirst().containsKey("expectedQty"));
    }

    @Test
    void cancellationStillReleasesFreezeWhenScopedLocationBecomesInactive() {
        receive(fixture, "2.0000");
        String countPublicId = createAndPlan(fixture);
        stockCountService.start(fixture.principal(), countPublicId);

        jdbc.update("""
                UPDATE warehouse_locations SET status = 'INACTIVE'
                WHERE tenant_id = ? AND public_id = ?
                """, fixture.tenantId(), fixture.locationPublicId());

        Map<String, Object> cancelled = stockCountService.cancel(fixture.principal(), countPublicId);
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals(Boolean.FALSE, cancelled.get("freezeStock"));
    }

    @Test
    void transferIsBlockedWhenEitherSourceOrDestinationLocationIsFrozen() {
        receive(fixture, "9.0000");
        String destinationLocation = createLocation(fixture);

        String destinationCount = createAndPlan(fixture, destinationLocation);
        stockCountService.start(fixture.principal(), destinationCount);
        assertThrows(IllegalStateException.class, () -> transfer(fixture, destinationLocation));
        stockCountService.cancel(fixture.principal(), destinationCount);

        String sourceCount = createAndPlan(fixture, fixture.locationPublicId());
        stockCountService.start(fixture.principal(), sourceCount);
        assertThrows(IllegalStateException.class, () -> transfer(fixture, destinationLocation));
        stockCountService.cancel(fixture.principal(), sourceCount);

        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(new BigDecimal("9.0000"), onHand(fixture));
    }

    @Test
    void overlappingActiveCountCannotFreezeTheSameLocationTwice() {
        String firstCount = createAndPlan(fixture);
        Map<String, Object> firstStarted = stockCountService.start(fixture.principal(), firstCount);
        assertEquals(Boolean.TRUE, firstStarted.get("freezeStock"));

        String secondCount = createAndPlan(fixture);
        assertThrows(IllegalStateException.class,
                () -> stockCountService.start(fixture.principal(), secondCount));

        Map<String, Object> second = stockCountService.get(fixture.principal(), secondCount);
        assertEquals("PLANNED", second.get("status"));
        assertEquals(Boolean.FALSE, second.get("freezeStock"));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM stock_counts
                WHERE tenant_id = ? AND freeze_stock = 1 AND status = 'IN_PROGRESS'
                """, fixture.tenantId()));
    }


    @Test
    void openCountExposesExpectedQuantityOnlyToAnAuthorizedCounter() {
        receive(fixture, "6.0000");
        Map<String, Object> created = stockCountService.create(fixture.principal(),
                new WmsStockCountManagementService.CreateCountCommand(
                        fixture.warehousePublicId(), "SC-OPEN-" + UUID.randomUUID().toString().substring(0, 8),
                        "CYCLE_COUNT", "OPEN", List.of(fixture.locationPublicId()), null));
        String countPublicId = (String) created.get("publicId");
        stockCountService.plan(fixture.principal(), countPublicId);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> counterLines = (List<Map<String, Object>>) started.get("lines");
        assertTrue(counterLines.getFirst().containsKey("expectedQty"));

        jdbc.update("""
                DELETE rp FROM role_permissions rp
                JOIN membership_roles mr ON mr.role_id = rp.role_id AND mr.tenant_id = ?
                JOIN tenant_memberships tm ON tm.id = mr.membership_id AND tm.tenant_id = mr.tenant_id
                WHERE tm.public_id = ?
                  AND rp.permission_id = (SELECT id FROM permissions WHERE permission_code = 'WMS_STOCK_COUNT_RECORD')
                """, fixture.tenantId(), fixture.principal().membershipPublicId());

        Map<String, Object> readOnly = stockCountService.get(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readOnlyLines = (List<Map<String, Object>>) readOnly.get("lines");
        assertFalse(readOnlyLines.getFirst().containsKey("expectedQty"));
    }

    @Test
    void discrepancyRequiresIndependentStableRecount() {
        receive(fixture, "5.0000");
        String countPublicId = createAndPlan(fixture);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) started.get("lines");
        String linePublicId = (String) lines.getFirst().get("publicId");

        Map<String, Object> first = stockCountService.recordResult(fixture.principal(), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("4.0000"), List.of(), "Initial physical count"));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstRecorded = (Map<String, Object>) first.get("recordedResult");
        assertEquals(1, firstRecorded.get("countRound"));
        assertEquals("RECOUNT_REQUIRED", firstRecorded.get("lineStatus"));
        assertThrows(IllegalStateException.class, () -> stockCountService.recordResult(
                fixture.principal(), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("4.0000"), List.of(), "Same operator cannot recount")));

        WmsAuthenticatedPrincipal secondCounter = secondCounter(fixture);
        Map<String, Object> recount = stockCountService.recordResult(secondCounter, countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("4.0000"), List.of(), "Independent recount confirms discrepancy"));
        @SuppressWarnings("unchecked")
        Map<String, Object> recountRecorded = (Map<String, Object>) recount.get("recordedResult");
        assertEquals(2, recountRecorded.get("countRound"));
        assertEquals("COUNTED", recountRecorded.get("lineStatus"));
        assertEquals(2, count("""
                SELECT COUNT(*) FROM stock_count_results r
                JOIN stock_count_lines l ON l.id = r.stock_count_line_id AND l.tenant_id = r.tenant_id
                WHERE r.tenant_id = ? AND l.public_id = ?
                """, fixture.tenantId(), linePublicId));
    }

    @Test
    void serialResultPersistsOnlyDistinctSerialsMatchingTheCountedQuantity() {
        fixture = fixture("SERIAL");
        receiveSerials(fixture, List.of("SERIAL-COUNT-001", "SERIAL-COUNT-002"));
        String countPublicId = createAndPlan(fixture);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) started.get("lines");
        String linePublicId = (String) lines.getFirst().get("publicId");
        List<String> serialPublicIds = jdbc.queryForList("""
                SELECT public_id FROM inventory_serials
                WHERE tenant_id = ? AND item_id = (
                  SELECT id FROM items WHERE tenant_id = ? AND public_id = ?
                )
                ORDER BY serial_number
                """, String.class, fixture.tenantId(), fixture.tenantId(), fixture.itemPublicId());
        assertEquals(2, serialPublicIds.size());

        assertThrows(IllegalArgumentException.class, () -> stockCountService.recordResult(
                fixture.principal(), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("2.0000"),
                        List.of(serialPublicIds.getFirst(), serialPublicIds.getFirst()), "Duplicate serial scan")));

        Map<String, Object> recorded = stockCountService.recordResult(fixture.principal(), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("2.0000"), serialPublicIds, "Two exact serial observations"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) recorded.get("recordedResult");
        assertEquals("COUNTED", result.get("lineStatus"));
        assertEquals(2, count("""
                SELECT COUNT(*) FROM stock_count_result_serials s
                JOIN stock_count_results r ON r.id = s.stock_count_result_id AND r.tenant_id = s.tenant_id
                JOIN stock_count_lines l ON l.id = r.stock_count_line_id AND l.tenant_id = r.tenant_id
                WHERE s.tenant_id = ? AND l.public_id = ?
                """, fixture.tenantId(), linePublicId));
    }

    @Test
    void activeCountCanAddAnUnexpectedItemAsZeroExpectedLine() {
        receive(fixture, "2.0000");
        String countPublicId = createAndPlan(fixture);
        stockCountService.start(fixture.principal(), countPublicId);
        String unexpectedItemPublicId = createItem(fixture, "NONE");

        Map<String, Object> updated = stockCountService.addLine(fixture.principal(), countPublicId,
                new WmsStockCountManagementService.AddCountLineCommand(
                        fixture.locationPublicId(), unexpectedItemPublicId, null, "AVAILABLE"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) updated.get("lines");
        assertEquals(2, lines.size());
        assertEquals(0, count("""
                SELECT COUNT(*) FROM stock_count_lines l
                JOIN stock_count_scopes s ON s.id = l.stock_count_scope_id AND s.tenant_id = l.tenant_id
                JOIN stock_counts c ON c.id = s.stock_count_id AND c.tenant_id = s.tenant_id
                JOIN items i ON i.id = l.item_id AND i.tenant_id = l.tenant_id
                WHERE c.tenant_id = ? AND c.public_id = ? AND i.public_id = ? AND l.expected_qty <> 0
                """, fixture.tenantId(), countPublicId, unexpectedItemPublicId));
    }

    @Test
    void countCannotUseForeignTenantWarehouseOrLocation() {
        Fixture other = fixture();
        assertThrows(IllegalArgumentException.class, () -> stockCountService.create(fixture.principal(),
                new WmsStockCountManagementService.CreateCountCommand(
                        other.warehousePublicId(), "CROSS-" + UUID.randomUUID(),
                        "CYCLE_COUNT", "BLIND", List.of(other.locationPublicId()), null)));
        assertEquals(0, count("SELECT COUNT(*) FROM stock_counts WHERE tenant_id = ?", fixture.tenantId()));
    }

    private String createAndPlan(Fixture f) {
        return createAndPlan(f, f.locationPublicId());
    }

    private String createAndPlan(Fixture f, String locationPublicId) {
        Map<String, Object> created = stockCountService.create(f.principal(), new WmsStockCountManagementService.CreateCountCommand(
                f.warehousePublicId(), "SC-" + UUID.randomUUID().toString().substring(0, 12),
                "CYCLE_COUNT", "BLIND", List.of(locationPublicId), "Stage 10 integration test"));
        String publicId = (String) created.get("publicId");
        assertEquals("DRAFT", created.get("status"));
        Map<String, Object> planned = stockCountService.plan(f.principal(), publicId);
        assertEquals("PLANNED", planned.get("status"));
        return publicId;
    }

    private String createLocation(Fixture f) {
        String publicId = UUID.randomUUID().toString();
        Long warehouseId = jdbc.queryForObject(
                "SELECT id FROM warehouses WHERE tenant_id = ? AND public_id = ?",
                Long.class, f.tenantId(), f.warehousePublicId());
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, 'Stock Count Transfer Destination', 'STORAGE', 'ACTIVE')
                """, publicId, f.tenantId(), warehouseId,
                "DEST_" + UUID.randomUUID().toString().substring(0, 8));
        return publicId;
    }

    private void transfer(Fixture f, String destinationLocationPublicId) {
        postingService.post(f.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "TRANSFER",
                List.of(
                        new WmsInventoryPostingService.PostingLeg(
                                f.warehousePublicId(), f.locationPublicId(), f.itemPublicId(),
                                null, null, null, "AVAILABLE", BigDecimal.ONE),
                        new WmsInventoryPostingService.PostingLeg(
                                f.warehousePublicId(), destinationLocationPublicId, f.itemPublicId(),
                                null, null, null, "AVAILABLE", BigDecimal.ONE)),
                "Frozen count transfer protection test"));
    }

    private void receive(Fixture f, String quantity) {
        postingService.post(f.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "RECEIPT",
                List.of(new WmsInventoryPostingService.PostingLeg(
                        f.warehousePublicId(), f.locationPublicId(), f.itemPublicId(),
                        null, null, null, "AVAILABLE", new BigDecimal(quantity))),
                "Stock count integration test receipt"));
    }

    private void receiveSerials(Fixture f, List<String> serialNumbers) {
        List<WmsInventoryPostingService.PostingLeg> legs = serialNumbers.stream()
                .map(serial -> new WmsInventoryPostingService.PostingLeg(
                        f.warehousePublicId(), f.locationPublicId(), f.itemPublicId(),
                        null, null, serial, "AVAILABLE", BigDecimal.ONE))
                .toList();
        postingService.post(f.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "RECEIPT", legs, "Serial stock count integration receipt"));
    }

    private String createItem(Fixture f, String trackingMethod) {
        String publicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO items (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method, status)
                VALUES (?, ?, ?, 'Additional Stock Count Item',
                        (SELECT id FROM units_of_measure WHERE uom_code = 'EA'), ?, 'ACTIVE')
                """, publicId, f.tenantId(),
                "SC_EXTRA_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), trackingMethod);
        return publicId;
    }

    private WmsAuthenticatedPrincipal secondCounter(Fixture f) {
        String userPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', 'Independent Count Operator', 'ACTIVE')
                """, userPublicId, "stock-count-second-" + UUID.randomUUID() + "@example.test");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE public_id = ?", Long.class, userPublicId);
        String membershipPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status, joined_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                """, membershipPublicId, f.tenantId(), userId);
        long membershipId = jdbc.queryForObject("SELECT id FROM tenant_memberships WHERE public_id = ?",
                Long.class, membershipPublicId);
        long roleId = jdbc.queryForObject("""
                SELECT role_id FROM membership_roles
                WHERE tenant_id = ? AND membership_id = (
                  SELECT id FROM tenant_memberships WHERE tenant_id = ? AND public_id = ?
                )
                """, Long.class, f.tenantId(), f.tenantId(), f.principal().membershipPublicId());
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                f.tenantId(), membershipId, roleId);
        return new WmsAuthenticatedPrincipal(userPublicId, f.tenantId(),
                jdbc.queryForObject("SELECT public_id FROM tenants WHERE id = ?", String.class, f.tenantId()),
                membershipId, membershipPublicId, Set.of());
    }

    private BigDecimal onHand(Fixture f) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand_qty), 0) FROM inventory_balances
                WHERE tenant_id = ? AND warehouse_id = (
                  SELECT id FROM warehouses WHERE tenant_id = ? AND public_id = ?
                ) AND location_id = (
                  SELECT id FROM warehouse_locations WHERE tenant_id = ? AND public_id = ?
                )
                """, BigDecimal.class, f.tenantId(), f.tenantId(), f.warehousePublicId(),
                f.tenantId(), f.locationPublicId());
    }

    private long count(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0L : result;
    }

    private Fixture fixture() {
        return fixture("NONE");
    }

    private Fixture fixture(String trackingMethod) {
        String tenantPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Stock Count Test Legal', 'Stock Count Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, tenantPublicId, "SC_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE public_id = ?", Long.class, tenantPublicId);

        String userPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', 'Stock Count Test User', 'ACTIVE')
                """, userPublicId, "stock-count-" + UUID.randomUUID() + "@example.test");
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
                VALUES (?, ?, ?, 'Stock Count Test Role', 'Integration test role', 0, 'ACTIVE')
                """, rolePublicId, tenantId, "SC_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE public_id = ?", Long.class, rolePublicId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenantId, membershipId, roleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE permission_code IN (
                  'WMS_INVENTORY_POST', 'WMS_STOCK_COUNT_READ', 'WMS_STOCK_COUNT_CREATE',
                  'WMS_STOCK_COUNT_PLAN', 'WMS_STOCK_COUNT_START', 'WMS_STOCK_COUNT_CANCEL',
                  'WMS_STOCK_COUNT_RECORD'
                )
                """, roleId);

        String warehousePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, 'Stock Count Test Warehouse', 'ACTIVE')
                """, warehousePublicId, tenantId, "WH_" + UUID.randomUUID().toString().substring(0, 8));
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE public_id = ?",
                Long.class, warehousePublicId);

        String locationPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, 'Stock Count Test Location', 'STORAGE', 'ACTIVE')
                """, locationPublicId, tenantId, warehouseId,
                "LOC_" + UUID.randomUUID().toString().substring(0, 8));

        String itemPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO items (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method, status)
                VALUES (?, ?, ?, 'Stock Count Test Item',
                        (SELECT id FROM units_of_measure WHERE uom_code = 'EA'), ?, 'ACTIVE')
                """, itemPublicId, tenantId, "SC_ITEM_" + UUID.randomUUID().toString().substring(0, 8), trackingMethod);

        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                userPublicId, tenantId, tenantPublicId, membershipId, membershipPublicId, Set.of());
        return new Fixture(tenantId, warehousePublicId, locationPublicId, itemPublicId, principal);
    }

    private record Fixture(long tenantId, String warehousePublicId, String locationPublicId,
                           String itemPublicId, WmsAuthenticatedPrincipal principal) {}
}
