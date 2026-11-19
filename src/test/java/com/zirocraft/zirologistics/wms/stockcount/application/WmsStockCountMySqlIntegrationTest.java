package com.zirocraft.zirologistics.wms.stockcount.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryReservationService;
import com.zirocraft.zirologistics.wms.adjustment.application.WmsInventoryAdjustmentService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    @Autowired private WmsInventoryReservationService reservationService;
    @Autowired private WmsInventoryAdjustmentService adjustmentService;
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

        assertEquals(4, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND subject_public_id = ? AND event_type = 'WMS_STOCK_COUNT_UPDATED'
                """, fixture.tenantId(), countPublicId));
        assertEquals(4, count("""
                SELECT COUNT(DISTINCT operation_ref) FROM tenant_audit_events
                WHERE tenant_id = ? AND subject_public_id = ? AND event_type = 'WMS_STOCK_COUNT_UPDATED'
                """, fixture.tenantId(), countPublicId));
        assertEquals("CANCELLED", jdbc.queryForObject("""
                SELECT scope_status FROM stock_count_scopes s
                JOIN stock_counts c ON c.id = s.stock_count_id AND c.tenant_id = s.tenant_id
                WHERE c.tenant_id = ? AND c.public_id = ?
                """, String.class, fixture.tenantId(), countPublicId));

        receive(fixture, "1.0000");
        assertEquals(new BigDecimal("13.5000"), onHand(fixture));
    }

    @Test
    void stockCountCannotStartWhileScopedInventoryHasOutstandingReservations() {
        receive(fixture, "5.0000");
        reservationService.reserve(fixture.principal(),
                new WmsInventoryReservationService.ReservationCommand(
                        fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                        null, BigDecimal.ONE, List.of()));
        String countPublicId = createAndPlan(fixture);

        assertThrows(IllegalStateException.class,
                () -> stockCountService.start(fixture.principal(), countPublicId));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("""
                SELECT freeze_stock FROM stock_counts
                WHERE tenant_id = ? AND public_id = ?
                """, Boolean.class, fixture.tenantId(), countPublicId));
        BigDecimal reserved = jdbc.queryForObject("""
                SELECT reserved_qty FROM inventory_balances
                WHERE tenant_id = ? AND warehouse_id = (
                  SELECT id FROM warehouses WHERE tenant_id = ? AND public_id = ?
                ) AND location_id = (
                  SELECT id FROM warehouse_locations WHERE tenant_id = ? AND public_id = ?
                ) AND item_id = (
                  SELECT id FROM items WHERE tenant_id = ? AND public_id = ?
                ) AND lot_id IS NULL AND stock_status = 'AVAILABLE'
                """, BigDecimal.class, fixture.tenantId(), fixture.tenantId(), fixture.warehousePublicId(),
                fixture.tenantId(), fixture.locationPublicId(), fixture.tenantId(), fixture.itemPublicId());
        assertEquals(new BigDecimal("1.0000"), reserved);
        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
    }

    @Test
    void reservationCannotChangeAvailabilityInsideAFrozenCountLocation() {
        receive(fixture, "5.0000");
        String countPublicId = createAndPlan(fixture);
        stockCountService.start(fixture.principal(), countPublicId);

        assertThrows(IllegalStateException.class, () -> reservationService.reserve(fixture.principal(),
                new WmsInventoryReservationService.ReservationCommand(
                        fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                        null, BigDecimal.ONE, List.of())));

        BigDecimal reserved = jdbc.queryForObject("""
                SELECT reserved_qty FROM inventory_balances
                WHERE tenant_id = ? AND warehouse_id = (
                  SELECT id FROM warehouses WHERE tenant_id = ? AND public_id = ?
                ) AND location_id = (
                  SELECT id FROM warehouse_locations WHERE tenant_id = ? AND public_id = ?
                ) AND item_id = (
                  SELECT id FROM items WHERE tenant_id = ? AND public_id = ?
                ) AND lot_id IS NULL AND stock_status = 'AVAILABLE'
                """, BigDecimal.class, fixture.tenantId(), fixture.tenantId(), fixture.warehousePublicId(),
                fixture.tenantId(), fixture.locationPublicId(), fixture.tenantId(), fixture.itemPublicId());
        assertEquals(new BigDecimal("0.0000"), reserved);
        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
    }

    @Test
    void inventoryAdjustmentReadApisAreTenantScoped() {
        Fixture foreign = fixture();
        String ownAdjustment = insertDraftAdjustment(fixture);
        String foreignAdjustment = insertDraftAdjustment(foreign);

        assertEquals(ownAdjustment, adjustmentService.get(fixture.principal(), ownAdjustment).get("publicId"));
        assertThrows(IllegalArgumentException.class,
                () -> adjustmentService.get(fixture.principal(), foreignAdjustment));
        assertEquals(1, adjustmentService.list(fixture.principal()).size());
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
    void receiptTransferAndStockCountSnapshotReconcileAcrossBothLocations() {
        receive(fixture, "10.0000");
        String destinationLocation = createLocation(fixture);

        postingService.post(fixture.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "TRANSFER",
                List.of(
                        new WmsInventoryPostingService.PostingLeg(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, null, null, "AVAILABLE", new BigDecimal("3.0000")),
                        new WmsInventoryPostingService.PostingLeg(
                                fixture.warehousePublicId(), destinationLocation, fixture.itemPublicId(),
                                null, null, null, "AVAILABLE", new BigDecimal("3.0000"))),
                "Inventory lifecycle integration transfer"));

        Map<String, Object> created = stockCountService.create(fixture.principal(),
                new WmsStockCountManagementService.CreateCountCommand(
                        fixture.warehousePublicId(), "LIFECYCLE-" + UUID.randomUUID().toString().substring(0, 8),
                        "CYCLE_COUNT", "BLIND", List.of(fixture.locationPublicId(), destinationLocation),
                        "Receipt, transfer and count lifecycle integration"));
        String countPublicId = (String) created.get("publicId");
        stockCountService.plan(fixture.principal(), countPublicId);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);

        Map<String, BigDecimal> expectedByLocation = new java.util.HashMap<>();
        jdbc.query("""
                SELECT l.public_id, scl.expected_qty
                FROM stock_count_lines scl
                JOIN stock_count_scopes scs
                  ON scs.id = scl.stock_count_scope_id AND scs.tenant_id = scl.tenant_id
                JOIN stock_counts sc
                  ON sc.id = scs.stock_count_id AND sc.tenant_id = scs.tenant_id
                JOIN warehouse_locations l
                  ON l.id = scl.location_id AND l.tenant_id = scl.tenant_id
                WHERE sc.tenant_id = ? AND sc.public_id = ?
                """, rs -> {
            while (rs.next()) expectedByLocation.put(rs.getString("public_id"), rs.getBigDecimal("expected_qty"));
        }, fixture.tenantId(), countPublicId);

        assertEquals(new BigDecimal("7.0000"), expectedByLocation.get(fixture.locationPublicId()));
        assertEquals(new BigDecimal("3.0000"), expectedByLocation.get(destinationLocation));
        assertEquals(Boolean.TRUE, started.get("freezeStock"));
        assertEquals(3, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND movement_type IN ('RECEIPT', 'TRANSFER')
                """, fixture.tenantId()));
        assertThrows(IllegalStateException.class, () -> postingService.post(fixture.principal(),
                new WmsInventoryPostingService.PostingCommand(UUID.randomUUID(), "TRANSFER",
                        List.of(
                                new WmsInventoryPostingService.PostingLeg(
                                        fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                        null, null, null, "AVAILABLE", BigDecimal.ONE),
                                new WmsInventoryPostingService.PostingLeg(
                                        fixture.warehousePublicId(), destinationLocation, fixture.itemPublicId(),
                                        null, null, null, "AVAILABLE", BigDecimal.ONE)),
                        "Movement must be blocked while lifecycle count is frozen")));
        assertEquals(new BigDecimal("7.0000"), onHand(fixture));
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
                  AND rp.permission_id IN (SELECT id FROM permissions WHERE permission_code IN ('WMS_STOCK_COUNT_RECORD', 'WMS_STOCK_COUNT_REVIEW', 'WMS_STOCK_COUNT_APPROVE'))
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


    @Test
    void varianceRequiresIndependentReviewAndFourEyesApprovalAndRemainsFrozen() {
        receive(fixture, "5.0000");
        String countPublicId = createAndPlan(fixture);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) started.get("lines");
        String linePublicId = (String) lines.getFirst().get("publicId");

        stockCountService.recordResult(fixture.principal(), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("4.0000"), List.of(), "Initial count"));
        WmsAuthenticatedPrincipal counter = secondCounter(fixture);
        Map<String, Object> recounted = stockCountService.recordResult(counter, countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("4.0000"), List.of(), "Independent recount"));
        assertEquals("COUNTED", ((Map<?, ?>) ((List<?>) recounted.get("lines")).getFirst()).get("lineStatus"));

        Map<String, Object> submitted = stockCountService.submit(fixture.principal(), countPublicId);
        assertEquals("SUBMITTED", submitted.get("status"));
        WmsAuthenticatedPrincipal reviewer = secondCounter(fixture);
        Map<String, Object> reviewed = stockCountService.reviewLine(reviewer, countPublicId, linePublicId);
        assertEquals("UNDER_REVIEW", reviewed.get("status"));
        assertEquals("REVIEWED", ((Map<?, ?>) ((List<?>) reviewed.get("lines")).getFirst()).get("lineStatus"));

        assertThrows(AccessDeniedException.class, () -> stockCountService.approve(fixture.principal(), countPublicId));
        assertThrows(AccessDeniedException.class, () -> stockCountService.approve(counter, countPublicId));
        assertThrows(AccessDeniedException.class, () -> stockCountService.approve(reviewer, countPublicId));

        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        Map<String, Object> approved = stockCountService.approve(approver, countPublicId);
        assertEquals("APPROVED", approved.get("status"));
        assertEquals(Boolean.TRUE, approved.get("freezeStock"));
        assertThrows(IllegalStateException.class, () -> receive(fixture, "1.0000"));
        assertEquals(3, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND subject_public_id = ?
                  AND event_type = 'WMS_STOCK_COUNT_UPDATED'
                  AND JSON_UNQUOTE(JSON_EXTRACT(change_payload, '$.action')) IN ('SUBMIT','REVIEW_LINE','APPROVE')
                """, fixture.tenantId(), countPublicId));
    }

    @Test
    void approvedVariancePostsOnlyThroughItsLinkedAdjustmentAndReleasesFreezeAtomically() {
        receive(fixture, "5.0000");
        String countPublicId = createAndPlan(fixture);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) started.get("lines");
        String linePublicId = (String) lines.getFirst().get("publicId");

        stockCountService.recordResult(fixture.principal(), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("4.0000"), List.of(), "Initial physical count"));
        WmsAuthenticatedPrincipal counter = secondCounter(fixture);
        stockCountService.recordResult(counter, countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("4.0000"), List.of(), "Independent recount"));
        stockCountService.submit(fixture.principal(), countPublicId);
        WmsAuthenticatedPrincipal reviewer = secondCounter(fixture);
        stockCountService.reviewLine(reviewer, countPublicId, linePublicId);
        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        assertEquals("APPROVED", stockCountService.approve(approver, countPublicId).get("status"));

        assertThrows(IllegalStateException.class, () -> receive(fixture, "1.0000"));
        Map<String, Object> created = adjustmentService.createFromApprovedCount(fixture.principal(), countPublicId,
                new WmsInventoryAdjustmentService.CreateFromCountCommand(
                        "ADJ-" + UUID.randomUUID().toString().substring(0, 12), "Count variance"));
        String adjustmentPublicId = (String) created.get("publicId");
        assertEquals("DRAFT", created.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocations = (List<Map<String, Object>>) created.get("allocations");
        assertEquals(new BigDecimal("-1.0000"), allocations.getFirst().get("adjustment_qty"));

        adjustmentService.submit(fixture.principal(), adjustmentPublicId);
        assertThrows(AccessDeniedException.class,
                () -> adjustmentService.approve(fixture.principal(), adjustmentPublicId, "Self approval"));
        Map<String, Object> approvedAdjustment =
                adjustmentService.approve(approver, adjustmentPublicId, "Reviewed variance");
        assertEquals("APPROVED", approvedAdjustment.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviewedLines = (List<Map<String, Object>>) approvedAdjustment.get("lines");
        assertEquals("REVIEWED", reviewedLines.getFirst().get("status"));
        Map<String, Object> posted = adjustmentService.post(approver, adjustmentPublicId);
        assertEquals("POSTED", posted.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> adjustedLines = (List<Map<String, Object>>) posted.get("lines");
        assertEquals("ADJUSTED", adjustedLines.getFirst().get("status"));
        assertEquals(new BigDecimal("4.0000"), onHand(fixture));
        assertEquals("COMPLETED", jdbc.queryForObject("""
                SELECT status FROM stock_counts WHERE tenant_id = ? AND public_id = ?
                """, String.class, fixture.tenantId(), countPublicId));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("""
                SELECT freeze_stock FROM stock_counts WHERE tenant_id = ? AND public_id = ?
                """, Boolean.class, fixture.tenantId(), countPublicId));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND movement_type = 'ADJUSTMENT_OUT'
                  AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId()));
        assertEquals(4, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND subject_public_id = ?
                  AND event_type = 'WMS_INVENTORY_ADJUSTMENT_UPDATED'
                """, fixture.tenantId(), adjustmentPublicId));
        assertThrows(IllegalStateException.class,
                () -> adjustmentService.createFromApprovedCount(fixture.principal(), countPublicId,
                        new WmsInventoryAdjustmentService.CreateFromCountCommand(
                                "ADJ-DUP-" + UUID.randomUUID().toString().substring(0, 8), null)));
    }

    @Test
    void rejectedAdjustmentDoesNotPostAndAllowsAReplacementDraft() {
        receive(fixture, "5.0000");
        String countPublicId = createAndPlan(fixture);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) started.get("lines");
        String linePublicId = (String) lines.getFirst().get("publicId");

        stockCountService.recordResult(fixture.principal(), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("4.0000"), List.of(), "Initial physical count"));
        stockCountService.recordResult(secondCounter(fixture), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("4.0000"), List.of(), "Independent recount"));
        stockCountService.submit(fixture.principal(), countPublicId);
        stockCountService.reviewLine(secondCounter(fixture), countPublicId, linePublicId);
        stockCountService.approve(secondCounter(fixture), countPublicId);

        Map<String, Object> created = adjustmentService.createFromApprovedCount(fixture.principal(), countPublicId,
                new WmsInventoryAdjustmentService.CreateFromCountCommand(
                        "ADJ-REJ-" + UUID.randomUUID().toString().substring(0, 10), null));
        String rejectedPublicId = (String) created.get("publicId");
        adjustmentService.submit(fixture.principal(), rejectedPublicId);
        Map<String, Object> rejected = adjustmentService.reject(
                secondCounter(fixture), rejectedPublicId, "Variance evidence requires correction");
        assertEquals("REJECTED", rejected.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rejectedLines = (List<Map<String, Object>>) rejected.get("lines");
        assertEquals("CANCELLED", rejectedLines.getFirst().get("status"));

        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId()));
        assertEquals("APPROVED", jdbc.queryForObject("""
                SELECT status FROM stock_counts WHERE tenant_id = ? AND public_id = ?
                """, String.class, fixture.tenantId(), countPublicId));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                SELECT freeze_stock FROM stock_counts WHERE tenant_id = ? AND public_id = ?
                """, Boolean.class, fixture.tenantId(), countPublicId));

        Map<String, Object> replacement = adjustmentService.createFromApprovedCount(fixture.principal(), countPublicId,
                new WmsInventoryAdjustmentService.CreateFromCountCommand(
                        "ADJ-RETRY-" + UUID.randomUUID().toString().substring(0, 10), null));
        assertEquals("DRAFT", replacement.get("status"));
        assertEquals(2, count("""
                SELECT COUNT(*) FROM stock_count_adjustment_links
                WHERE tenant_id = ? AND stock_count_id = (
                  SELECT id FROM stock_counts WHERE tenant_id = ? AND public_id = ?
                )
                """, fixture.tenantId(), fixture.tenantId(), countPublicId));
    }

    @Test
    void serializedCountVariancePostsTheExactMissingSerialThroughAdjustmentWorkflow() {
        fixture = fixture("SERIAL");
        receiveSerials(fixture, List.of("SERIAL-ADJUST-001", "SERIAL-ADJUST-002"));
        String countPublicId = createAndPlan(fixture);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) started.get("lines");
        String linePublicId = (String) lines.getFirst().get("publicId");
        List<String> serialIds = jdbc.queryForList("""
                SELECT public_id FROM inventory_serials
                WHERE tenant_id = ? AND item_id = (
                  SELECT id FROM items WHERE tenant_id = ? AND public_id = ?
                )
                ORDER BY serial_number
                """, String.class, fixture.tenantId(), fixture.tenantId(), fixture.itemPublicId());
        assertEquals(2, serialIds.size());
        List<String> physicallyObserved = List.of(serialIds.getFirst());

        stockCountService.recordResult(fixture.principal(), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        BigDecimal.ONE, physicallyObserved, "One serial physically present"));
        WmsAuthenticatedPrincipal counter = secondCounter(fixture);
        stockCountService.recordResult(counter, countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        BigDecimal.ONE, physicallyObserved, "Independent serial recount"));

        stockCountService.submit(fixture.principal(), countPublicId);
        WmsAuthenticatedPrincipal reviewer = secondCounter(fixture);
        stockCountService.reviewLine(reviewer, countPublicId, linePublicId);
        WmsAuthenticatedPrincipal countApprover = secondCounter(fixture);
        stockCountService.approve(countApprover, countPublicId);

        Map<String, Object> created = adjustmentService.createFromApprovedCount(fixture.principal(), countPublicId,
                new WmsInventoryAdjustmentService.CreateFromCountCommand(
                        "ADJ-SER-" + UUID.randomUUID().toString().substring(0, 10), null));
        String adjustmentPublicId = (String) created.get("publicId");
        adjustmentService.submit(fixture.principal(), adjustmentPublicId);
        WmsAuthenticatedPrincipal adjustmentApprover = secondCounter(fixture);
        adjustmentService.approve(adjustmentApprover, adjustmentPublicId, "Serial variance verified");
        adjustmentService.post(adjustmentApprover, adjustmentPublicId);

        assertEquals("AVAILABLE", serialStatus(fixture, serialIds.getFirst()));
        assertEquals("LOST", serialStatus(fixture, serialIds.get(1)));
        assertEquals(BigDecimal.ONE.setScale(4), onHand(fixture));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_adjustment_line_serials
                WHERE tenant_id = ? AND observation_status = 'MISSING'
                """, fixture.tenantId()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND movement_type = 'ADJUSTMENT_OUT'
                  AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId()));
    }

    @Test
    void serializedCountIdentityVarianceRestoresFoundLostSerialAndMarksMissingSerialLost() {
        fixture = fixture("SERIAL");
        receiveSerials(fixture, List.of("SERIAL-BALANCE-001", "SERIAL-BALANCE-002", "SERIAL-BALANCE-003"));
        List<String> serialIds = jdbc.queryForList("""
                SELECT public_id FROM inventory_serials
                WHERE tenant_id = ? AND item_id = (
                  SELECT id FROM items WHERE tenant_id = ? AND public_id = ?
                )
                ORDER BY serial_number
                """, String.class, fixture.tenantId(), fixture.tenantId(), fixture.itemPublicId());
        assertEquals(3, serialIds.size());

        postingService.postTransferDispatch(fixture.principal(),
                new WmsInventoryPostingService.PostingCommand(UUID.randomUUID(), "TRANSFER_DISPATCH",
                        List.of(new WmsInventoryPostingService.PostingLeg(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, serialIds.get(2), null, "AVAILABLE", BigDecimal.ONE)),
                        "Prepare a known lost serial for count reconciliation"));
        postingService.markTransferSerialLost(fixture.principal(), serialIds.get(2));

        String countPublicId = createAndPlan(fixture);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) started.get("lines");
        String linePublicId = (String) lines.getFirst().get("publicId");
        List<String> physicallyObserved = List.of(serialIds.get(0), serialIds.get(2));

        stockCountService.recordResult(fixture.principal(), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        BigDecimal.valueOf(2), physicallyObserved, "One expected serial and one found lost serial"));
        WmsAuthenticatedPrincipal counter = secondCounter(fixture);
        stockCountService.recordResult(counter, countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        BigDecimal.valueOf(2), physicallyObserved, "Independent serial recount"));
        stockCountService.submit(fixture.principal(), countPublicId);
        stockCountService.reviewLine(secondCounter(fixture), countPublicId, linePublicId);
        stockCountService.approve(secondCounter(fixture), countPublicId);

        Map<String, Object> created = adjustmentService.createFromApprovedCount(fixture.principal(), countPublicId,
                new WmsInventoryAdjustmentService.CreateFromCountCommand(
                        "ADJ-BAL-" + UUID.randomUUID().toString().substring(0, 10), null));
        String adjustmentPublicId = (String) created.get("publicId");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocations = (List<Map<String, Object>>) created.get("allocations");
        assertEquals(new BigDecimal("0.0000"), allocations.getFirst().get("adjustment_qty"));

        adjustmentService.submit(fixture.principal(), adjustmentPublicId);
        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        adjustmentService.approve(approver, adjustmentPublicId, "Balanced serial identity variance");
        adjustmentService.post(approver, adjustmentPublicId);

        assertEquals("AVAILABLE", serialStatus(fixture, serialIds.get(0)));
        assertEquals("LOST", serialStatus(fixture, serialIds.get(1)));
        assertEquals("AVAILABLE", serialStatus(fixture, serialIds.get(2)));
        assertEquals(new BigDecimal("2.0000"), onHand(fixture));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND movement_type = 'ADJUSTMENT_IN'
                  AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND movement_type = 'ADJUSTMENT_OUT'
                  AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId()));
    }

    @Test
    void approvedCountWithoutVarianceCompletesAndReleasesLocationFreeze() {
        receive(fixture, "3.0000");
        String countPublicId = createAndPlan(fixture);
        Map<String, Object> started = stockCountService.start(fixture.principal(), countPublicId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) started.get("lines");
        String linePublicId = (String) lines.getFirst().get("publicId");
        stockCountService.recordResult(fixture.principal(), countPublicId, linePublicId,
                new WmsStockCountManagementService.RecordCountResultCommand(
                        new BigDecimal("3.0000"), List.of(), "Exact physical count"));
        stockCountService.submit(fixture.principal(), countPublicId);

        WmsAuthenticatedPrincipal reviewer = secondCounter(fixture);
        stockCountService.reviewLine(reviewer, countPublicId, linePublicId);
        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        Map<String, Object> completed = stockCountService.approve(approver, countPublicId);
        assertEquals("COMPLETED", completed.get("status"));
        assertEquals(Boolean.FALSE, completed.get("freezeStock"));
        assertEquals("COMPLETED", jdbc.queryForObject("""
                SELECT scope_status FROM stock_count_scopes s
                JOIN stock_counts c ON c.id = s.stock_count_id AND c.tenant_id = s.tenant_id
                WHERE c.tenant_id = ? AND c.public_id = ?
                """, String.class, fixture.tenantId(), countPublicId));
        receive(fixture, "1.0000");
        assertEquals(new BigDecimal("4.0000"), onHand(fixture));
    }

    @Test
    void manualAdjustmentRejectsUnsupportedTypesAndSerialTrackedItems() {
        assertThrows(IllegalArgumentException.class, () -> adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-DAMAGE-" + UUID.randomUUID().toString().substring(0, 8), "DAMAGE",
                        "DAMAGE", "Status reclassification is not enabled yet", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", BigDecimal.ONE)))));

        Fixture serialFixture = fixture("SERIAL");
        assertThrows(IllegalArgumentException.class, () -> adjustmentService.createManual(serialFixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-SERIAL-" + UUID.randomUUID().toString().substring(0, 8), "CORRECTION",
                        "RECONCILE", "Serial evidence workflow not yet enabled", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                serialFixture.warehousePublicId(), serialFixture.locationPublicId(),
                                serialFixture.itemPublicId(), null, "AVAILABLE", BigDecimal.ONE)))));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_adjustments WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_adjustments WHERE tenant_id = ?", serialFixture.tenantId()));
    }

    @Test
    void manualCorrectionPostsOnlyAfterIndependentApprovalThroughTypedBoundary() {
        receive(fixture, "5.0000");
        Map<String, Object> created = adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-COR-" + UUID.randomUUID().toString().substring(0, 8), "CORRECTION",
                        "CYCLE_FIX", "Reconcile verified shelf discrepancy", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", new BigDecimal("7.0000")))));
        String adjustmentId = (String) created.get("publicId");
        assertEquals("DRAFT", created.get("status"));
        assertEquals("CORRECTION", created.get("adjustment_type"));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId()));

        adjustmentService.submit(fixture.principal(), adjustmentId);
        assertThrows(AccessDeniedException.class,
                () -> adjustmentService.approve(fixture.principal(), adjustmentId, "Creator cannot self-approve"));
        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        adjustmentService.approve(approver, adjustmentId, "Evidence reviewed independently");
        Map<String, Object> posted = adjustmentService.post(approver, adjustmentId);

        assertEquals("POSTED", posted.get("status"));
        assertEquals(new BigDecimal("7.0000"), onHand(fixture));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND movement_type = 'ADJUSTMENT_IN'
                  AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId()));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM stock_count_adjustment_links
                WHERE tenant_id = ? AND inventory_adjustment_id = (
                  SELECT id FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?
                )
                """, fixture.tenantId(), fixture.tenantId(), adjustmentId));
        assertEquals(4, count("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND subject_public_id = ?
                  AND event_type = 'WMS_INVENTORY_ADJUSTMENT_UPDATED'
                  AND JSON_UNQUOTE(JSON_EXTRACT(change_payload, '$.action'))
                      IN ('CREATE_MANUAL','SUBMIT','APPROVE','POST_MANUAL')
                """, fixture.tenantId(), adjustmentId));
    }

    @Test
    void manualLossAndFoundRequireCorrectVarianceDirection() {
        receive(fixture, "5.0000");
        assertThrows(IllegalArgumentException.class, () -> adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-LOSS-INVALID-" + UUID.randomUUID().toString().substring(0, 6), "LOSS",
                        "LOSS", "Loss reason", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", new BigDecimal("6.0000"))))));
        assertEquals(new BigDecimal("5.0000"), onHand(fixture));

        Map<String, Object> validLoss = adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-LOSS-" + UUID.randomUUID().toString().substring(0, 8), "LOSS",
                        "LOSS", "Verified missing stock", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", new BigDecimal("3.0000")))));
        String lossId = (String) validLoss.get("publicId");
        adjustmentService.submit(fixture.principal(), lossId);
        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        adjustmentService.approve(approver, lossId, "Loss independently verified");
        assertEquals("POSTED", adjustmentService.post(approver, lossId).get("status"));
        assertEquals(new BigDecimal("3.0000"), onHand(fixture));
    }

    @Test
    void manualFoundAdjustmentCanAddVerifiedQuantityFromZeroBaseline() {
        Map<String, Object> created = adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-FOUND-" + UUID.randomUUID().toString().substring(0, 8), "FOUND",
                        "FOUND", "Verified unrecorded stock", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", new BigDecimal("2.0000")))));
        String adjustmentId = (String) created.get("publicId");
        adjustmentService.submit(fixture.principal(), adjustmentId);
        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        adjustmentService.approve(approver, adjustmentId, "Found quantity independently verified");
        Map<String, Object> posted = adjustmentService.post(approver, adjustmentId);
        assertEquals("POSTED", posted.get("status"));
        assertEquals(new BigDecimal("2.0000"), onHand(fixture));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND movement_type = 'ADJUSTMENT_IN'
                  AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId()));
    }

    @Test
    void manualAdjustmentRejectsStaleBaselineAtApproval() {
        receive(fixture, "5.0000");
        Map<String, Object> created = adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-STALE-" + UUID.randomUUID().toString().substring(0, 8), "CORRECTION",
                        "RECONCILE", "Baseline stale test", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", new BigDecimal("7.0000")))));
        String adjustmentId = (String) created.get("publicId");
        adjustmentService.submit(fixture.principal(), adjustmentId);
        receive(fixture, "1.0000");
        assertThrows(IllegalStateException.class,
                () -> adjustmentService.approve(secondCounter(fixture), adjustmentId, "Stale baseline"));
        assertEquals("SUBMITTED", jdbc.queryForObject("""
                SELECT status FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?
                """, String.class, fixture.tenantId(), adjustmentId));
        assertEquals(new BigDecimal("6.0000"), onHand(fixture));
    }

    @Test
    void manualNegativeAdjustmentCannotConsumeReservedQuantity() {
        receive(fixture, "5.0000");
        reservationService.reserve(fixture.principal(),
                new WmsInventoryReservationService.ReservationCommand(
                        fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                        null, new BigDecimal("4.0000"), List.of()));
        Map<String, Object> created = adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-RESERVED-" + UUID.randomUUID().toString().substring(0, 8), "LOSS",
                        "LOSS", "Reserved stock must remain allocated", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", BigDecimal.ZERO.setScale(4)))));
        String adjustmentId = (String) created.get("publicId");
        adjustmentService.submit(fixture.principal(), adjustmentId);
        assertThrows(IllegalStateException.class,
                () -> adjustmentService.approve(secondCounter(fixture), adjustmentId, "Insufficient available quantity"));
        assertEquals("SUBMITTED", jdbc.queryForObject("""
                SELECT status FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?
                """, String.class, fixture.tenantId(), adjustmentId));
        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
    }

    @Test
    void manualAdjustmentRevalidatesBaselineAgainAtPosting() {
        receive(fixture, "5.0000");
        Map<String, Object> created = adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-POST-STALE-" + UUID.randomUUID().toString().substring(0, 8), "CORRECTION",
                        "RECONCILE", "Posting-time baseline test", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", new BigDecimal("7.0000")))));
        String adjustmentId = (String) created.get("publicId");
        adjustmentService.submit(fixture.principal(), adjustmentId);
        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        adjustmentService.approve(approver, adjustmentId, "Baseline valid at approval");
        receive(fixture, "1.0000");
        long ledgerBefore = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId());
        assertThrows(IllegalStateException.class, () -> adjustmentService.post(approver, adjustmentId));
        assertEquals(ledgerBefore, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals("APPROVED", jdbc.queryForObject("""
                SELECT status FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?
                """, String.class, fixture.tenantId(), adjustmentId));
        assertEquals(new BigDecimal("6.0000"), onHand(fixture));
    }

    @Test
    void manualAdjustmentPostingRollsBackEarlierAllocationsWhenLaterAllocationIsStale() {
        receive(fixture, "5.0000");
        String secondItemPublicId = createItem(fixture, "NONE");
        postingService.post(fixture.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "RECEIPT",
                List.of(new WmsInventoryPostingService.PostingLeg(
                        fixture.warehousePublicId(), fixture.locationPublicId(), secondItemPublicId,
                        null, null, null, "AVAILABLE", BigDecimal.ONE)),
                "Seed second bucket for atomic rollback test"));

        Map<String, Object> created = adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-ATOMIC-" + UUID.randomUUID().toString().substring(0, 8), "CORRECTION",
                        "RECONCILE", "Atomic multi-allocation rollback test", null,
                        List.of(
                                new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                        fixture.warehousePublicId(), fixture.locationPublicId(),
                                        fixture.itemPublicId(), null, "AVAILABLE", new BigDecimal("6.0000")),
                                new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                        fixture.warehousePublicId(), fixture.locationPublicId(),
                                        secondItemPublicId, null, "AVAILABLE", new BigDecimal("2.0000")))));
        String adjustmentId = (String) created.get("publicId");
        adjustmentService.submit(fixture.principal(), adjustmentId);
        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        adjustmentService.approve(approver, adjustmentId, "Both baselines initially verified");

        // The second item has the larger generated ID, so the first allocation posts
        // before this receipt makes the later allocation's approved baseline stale.
        postingService.post(fixture.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "RECEIPT",
                List.of(new WmsInventoryPostingService.PostingLeg(
                        fixture.warehousePublicId(), fixture.locationPublicId(), secondItemPublicId,
                        null, null, null, "AVAILABLE", BigDecimal.ONE)),
                "Make the second allocation stale after approval"));

        long adjustmentLedgerBefore = count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId());
        assertThrows(IllegalStateException.class, () -> adjustmentService.post(approver, adjustmentId));

        assertEquals(new BigDecimal("5.0000"), jdbc.queryForObject("""
                SELECT on_hand_qty FROM inventory_balances
                WHERE tenant_id = ? AND item_id = (
                  SELECT id FROM items WHERE tenant_id = ? AND public_id = ?
                ) AND location_id = (
                  SELECT id FROM warehouse_locations WHERE tenant_id = ? AND public_id = ?
                ) AND lot_id IS NULL AND stock_status = 'AVAILABLE'
                """, BigDecimal.class, fixture.tenantId(), fixture.tenantId(), fixture.itemPublicId(),
                fixture.tenantId(), fixture.locationPublicId()));
        assertEquals(new BigDecimal("2.0000"), jdbc.queryForObject("""
                SELECT on_hand_qty FROM inventory_balances
                WHERE tenant_id = ? AND item_id = (
                  SELECT id FROM items WHERE tenant_id = ? AND public_id = ?
                ) AND location_id = (
                  SELECT id FROM warehouse_locations WHERE tenant_id = ? AND public_id = ?
                ) AND lot_id IS NULL AND stock_status = 'AVAILABLE'
                """, BigDecimal.class, fixture.tenantId(), fixture.tenantId(), secondItemPublicId,
                fixture.tenantId(), fixture.locationPublicId()));
        assertEquals(adjustmentLedgerBefore, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId()));
        assertEquals("APPROVED", jdbc.queryForObject("""
                SELECT status FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?
                """, String.class, fixture.tenantId(), adjustmentId));
    }

    @Test
    void manualAdjustmentPostingIsBlockedByActiveFrozenStockCount() {
        receive(fixture, "5.0000");
        Map<String, Object> created = adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-FROZEN-" + UUID.randomUUID().toString().substring(0, 8), "CORRECTION",
                        "RECONCILE", "Frozen location test", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", new BigDecimal("7.0000")))));
        String adjustmentId = (String) created.get("publicId");
        adjustmentService.submit(fixture.principal(), adjustmentId);
        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        adjustmentService.approve(approver, adjustmentId, "Approved before count freeze");
        String countId = createAndPlan(fixture);
        stockCountService.start(fixture.principal(), countId);
        assertThrows(IllegalStateException.class, () -> adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-CREATE-FROZEN-" + UUID.randomUUID().toString().substring(0, 8), "CORRECTION",
                        "RECONCILE", "Cannot create against frozen location", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", new BigDecimal("7.0000"))))));
        long ledgerBefore = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId());
        assertThrows(IllegalStateException.class, () -> adjustmentService.post(approver, adjustmentId));
        assertEquals(ledgerBefore, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals("APPROVED", jdbc.queryForObject("""
                SELECT status FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?
                """, String.class, fixture.tenantId(), adjustmentId));
        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
    }

    @Test
    void manualAdjustmentCreationRequiresDedicatedCreatePermission() {
        jdbc.update("""
                DELETE rp FROM role_permissions rp
                JOIN membership_roles mr ON mr.role_id = rp.role_id AND mr.tenant_id = ?
                JOIN tenant_memberships tm ON tm.id = mr.membership_id AND tm.tenant_id = mr.tenant_id
                WHERE tm.public_id = ?
                  AND rp.permission_id = (SELECT id FROM permissions
                                          WHERE permission_code = 'WMS_INVENTORY_ADJUSTMENT_CREATE')
                """, fixture.tenantId(), fixture.principal().membershipPublicId());

        assertThrows(AccessDeniedException.class, () -> adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-NO-CREATE-" + UUID.randomUUID().toString().substring(0, 8), "CORRECTION",
                        "RECONCILE", "Permission denial test", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", BigDecimal.ONE)))));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_adjustments WHERE tenant_id = ?", fixture.tenantId()));
    }

    @Test
    void manualAdjustmentPublicIdCannotBePostedAcrossTenants() {
        receive(fixture, "5.0000");
        Map<String, Object> created = adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-TENANT-" + UUID.randomUUID().toString().substring(0, 8), "CORRECTION",
                        "RECONCILE", "Tenant boundary test", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", new BigDecimal("7.0000")))));
        String adjustmentId = (String) created.get("publicId");
        adjustmentService.submit(fixture.principal(), adjustmentId);
        adjustmentService.approve(secondCounter(fixture), adjustmentId, "Independent approval");

        Fixture otherTenant = fixture();
        assertThrows(IllegalArgumentException.class,
                () -> adjustmentService.post(otherTenant.principal(), adjustmentId));
        assertEquals("APPROVED", jdbc.queryForObject("""
                SELECT status FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?
                """, String.class, fixture.tenantId(), adjustmentId));
        assertEquals(new BigDecimal("5.0000"), onHand(fixture));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND reference_type = 'INVENTORY_ADJUSTMENT'
                """, fixture.tenantId()));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?",
                otherTenant.tenantId(), adjustmentId));
    }


    @Test
    void stockCountStartRacingWithReceiptKeepsSnapshotAndFreezeAtOneConsistentBoundary() throws Exception {
        receive(fixture, "5.0000");
        String countPublicId = createAndPlan(fixture);

        List<RuntimeException> outcomes = race(
                () -> stockCountService.start(fixture.principal(), countPublicId),
                () -> receive(fixture, "1.0000"));

        assertNull(outcomes.getFirst(), "Stock count start must complete successfully");
        RuntimeException receiptFailure = outcomes.get(1);
        if (receiptFailure != null) {
            assertInstanceOf(IllegalStateException.class, receiptFailure);
        }

        BigDecimal snapshot = jdbc.queryForObject("""
                SELECT l.expected_qty
                FROM stock_count_lines l
                JOIN stock_count_scopes s ON s.id = l.stock_count_scope_id AND s.tenant_id = l.tenant_id
                JOIN stock_counts c ON c.id = s.stock_count_id AND c.tenant_id = s.tenant_id
                WHERE c.tenant_id = ? AND c.public_id = ?
                """, BigDecimal.class, fixture.tenantId(), countPublicId);
        BigDecimal finalOnHand = onHand(fixture);
        assertEquals(snapshot, finalOnHand,
                "A receipt must either precede the snapshot or be rejected after the freeze");
        assertTrue(snapshot.compareTo(new BigDecimal("5.0000")) == 0
                        || snapshot.compareTo(new BigDecimal("6.0000")) == 0);
        assertEquals(receiptFailure == null ? new BigDecimal("6.0000") : new BigDecimal("5.0000"), finalOnHand);
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                SELECT freeze_stock FROM stock_counts WHERE tenant_id = ? AND public_id = ?
                """, Boolean.class, fixture.tenantId(), countPublicId));
    }

    @Test
    void adjustmentPostingRacingWithReceiptRejectsStaleBaselineWithoutPartialPosting() throws Exception {
        receive(fixture, "5.0000");
        Map<String, Object> created = adjustmentService.createManual(fixture.principal(),
                new WmsInventoryAdjustmentService.CreateManualCommand(
                        "MAN-RACE-" + UUID.randomUUID().toString().substring(0, 8), "CORRECTION",
                        "RECONCILE", "Concurrent receipt baseline validation", null,
                        List.of(new WmsInventoryAdjustmentService.ManualAllocationCommand(
                                fixture.warehousePublicId(), fixture.locationPublicId(), fixture.itemPublicId(),
                                null, "AVAILABLE", new BigDecimal("7.0000")))));
        String adjustmentId = (String) created.get("publicId");
        adjustmentService.submit(fixture.principal(), adjustmentId);
        WmsAuthenticatedPrincipal approver = secondCounter(fixture);
        adjustmentService.approve(approver, adjustmentId, "Baseline verified before concurrent receipt");

        List<RuntimeException> outcomes = race(
                () -> adjustmentService.post(approver, adjustmentId),
                () -> receive(fixture, "1.0000"));

        assertNull(outcomes.get(1), "The independent receipt must remain valid");
        RuntimeException adjustmentFailure = outcomes.getFirst();
        if (adjustmentFailure != null) {
            assertInstanceOf(IllegalStateException.class, adjustmentFailure);
            assertEquals("APPROVED", jdbc.queryForObject("""
                    SELECT status FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?
                    """, String.class, fixture.tenantId(), adjustmentId));
            assertEquals(0, count("""
                    SELECT COUNT(*) FROM inventory_ledger
                    WHERE tenant_id = ? AND reference_type = 'INVENTORY_ADJUSTMENT'
                    """, fixture.tenantId()));
            assertEquals(new BigDecimal("6.0000"), onHand(fixture));
        } else {
            assertEquals("POSTED", jdbc.queryForObject("""
                    SELECT status FROM inventory_adjustments WHERE tenant_id = ? AND public_id = ?
                    """, String.class, fixture.tenantId(), adjustmentId));
            assertEquals(1, count("""
                    SELECT COUNT(*) FROM inventory_ledger
                    WHERE tenant_id = ? AND reference_type = 'INVENTORY_ADJUSTMENT'
                    """, fixture.tenantId()));
            assertEquals(new BigDecimal("8.0000"), onHand(fixture));
        }
    }

    private List<RuntimeException> race(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RuntimeException> one = executor.submit(() -> runAfterBarrier(ready, start, first));
            Future<RuntimeException> two = executor.submit(() -> runAfterBarrier(ready, start, second));
            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "Both independent database workers must reach the start barrier");
            start.countDown();
            return java.util.Arrays.asList(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private RuntimeException runAfterBarrier(
            CountDownLatch ready, CountDownLatch start, Runnable action) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrency start barrier timed out");
        }
        try {
            action.run();
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
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

    private String serialStatus(Fixture f, String serialPublicId) {
        return jdbc.queryForObject("""
                SELECT status FROM inventory_serials
                WHERE tenant_id = ? AND public_id = ?
                """, String.class, f.tenantId(), serialPublicId);
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

    private String insertDraftAdjustment(Fixture f) {
        String publicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO inventory_adjustments
                  (public_id, tenant_id, warehouse_id, document_number, adjustment_type, status,
                   reason_code, reason_description, created_by_membership_id)
                VALUES (?, ?, (
                  SELECT id FROM warehouses WHERE tenant_id = ? AND public_id = ?
                ), ?, 'PHYSICAL_COUNT', 'DRAFT', 'PHYSICAL_COUNT', 'Tenant isolation test', ?)
                """, publicId, f.tenantId(), f.tenantId(), f.warehousePublicId(),
                "ADJ-READ-" + UUID.randomUUID().toString().substring(0, 8),
                f.principal().membershipId());
        return publicId;
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
                  'WMS_INVENTORY_POST', 'WMS_INVENTORY_RESERVE',
                  'WMS_STOCK_COUNT_READ', 'WMS_STOCK_COUNT_CREATE',
                  'WMS_STOCK_COUNT_PLAN', 'WMS_STOCK_COUNT_START', 'WMS_STOCK_COUNT_CANCEL',
                  'WMS_STOCK_COUNT_RECORD', 'WMS_STOCK_COUNT_SUBMIT',
                  'WMS_STOCK_COUNT_REVIEW', 'WMS_STOCK_COUNT_APPROVE',
                  'WMS_INVENTORY_ADJUSTMENT_READ', 'WMS_INVENTORY_ADJUSTMENT_CREATE',
                  'WMS_INVENTORY_ADJUSTMENT_SUBMIT', 'WMS_INVENTORY_ADJUSTMENT_APPROVE',
                  'WMS_INVENTORY_ADJUSTMENT_REJECT', 'WMS_INVENTORY_ADJUSTMENT_POST'
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
