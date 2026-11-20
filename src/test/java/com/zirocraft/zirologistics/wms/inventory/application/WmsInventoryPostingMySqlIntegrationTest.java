package com.zirocraft.zirologistics.wms.inventory.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.reporting.application.WmsOperationalReportingService;
import com.zirocraft.zirologistics.wms.transfer.application.WmsInterWarehouseTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

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
    @Autowired private WmsOperationalReportingService reportingService;
    @Autowired private WmsInventoryReservationService reservationService;
    @Autowired private WmsInterWarehouseTransferService transferService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = fixture("NONE");
    }

    @Test
    void crossTenantInventoryPostingRejectsForeignAndMixedOwnershipReferencesWithoutSideEffects() {
        Fixture tenantB = fixture("NONE");
        long ledgerBeforeA = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId());
        long ledgerBeforeB = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", tenantB.tenantId());
        long auditBeforeA = count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ? AND event_type = 'INVENTORY_MOVEMENT_POSTED'", fixture.tenantId());
        long auditBeforeB = count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ? AND event_type = 'INVENTORY_MOVEMENT_POSTED'", tenantB.tenantId());

        WmsInventoryPostingService.PostingLeg foreignAll = leg(tenantB, null, null, "AVAILABLE", "1.0000");
        WmsInventoryPostingService.PostingLeg mixedForeignItem = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), fixture.locationPublicId(), tenantB.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("1.0000"));
        WmsInventoryPostingService.PostingLeg mixedForeignLocation = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), tenantB.locationPublicId(), fixture.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("1.0000"));

        assertThrows(RuntimeException.class, () -> post(fixture, UUID.randomUUID(), "RECEIPT", foreignAll),
                "Tenant A principal must not post using Tenant B warehouse, location, and item");
        assertThrows(RuntimeException.class, () -> post(fixture, UUID.randomUUID(), "RECEIPT", mixedForeignItem),
                "Tenant A principal must not post using Tenant B item");
        assertThrows(RuntimeException.class, () -> post(fixture, UUID.randomUUID(), "RECEIPT", mixedForeignLocation),
                "Tenant A principal must not post using Tenant B location");

        assertEquals(BigDecimal.ZERO.setScale(4), balance(fixture), "Tenant A stock must remain unchanged after cross-tenant rejection");
        assertEquals(BigDecimal.ZERO.setScale(4), balance(tenantB), "Tenant B stock must remain unchanged after cross-tenant rejection");
        assertEquals(ledgerBeforeA, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()),
                "Tenant A ledger must not record rejected cross-tenant attempts");
        assertEquals(ledgerBeforeB, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", tenantB.tenantId()),
                "Tenant B ledger must not record rejected cross-tenant attempts");
        assertEquals(auditBeforeA, count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ? AND event_type = 'INVENTORY_MOVEMENT_POSTED'", fixture.tenantId()),
                "Tenant A audit must not record rejected cross-tenant attempts");
        assertEquals(auditBeforeB, count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ? AND event_type = 'INVENTORY_MOVEMENT_POSTED'", tenantB.tenantId()),
                "Tenant B audit must not record rejected cross-tenant attempts");
    }

    @Test
    void operationalSummaryIsReadOnlyAndStrictlyScopedToAuthenticatedTenant() {
        post(fixture, UUID.randomUUID(), "RECEIPT",
                leg(fixture, null, null, "AVAILABLE", "5.0000"));
        Fixture otherTenant = fixture("NONE");
        post(otherTenant, UUID.randomUUID(), "RECEIPT",
                leg(otherTenant, null, null, "AVAILABLE", "9.0000"));

        long ledgerBefore = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id IN (?, ?)",
                fixture.tenantId(), otherTenant.tenantId());

        Map<String, Object> summary = reportingService.operationalSummary(fixture.principal());
        assertNotNull(summary.get("generatedAtUtc"));
        assertEquals("ALL_TENANT_DOCUMENTS_CURRENT_STATUS", summary.get("statusScope"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inventory = (Map<String, Object>) summary.get("inventoryPosition");
        assertEquals(new BigDecimal("5.0000"), inventory.get("onHandQty"));
        assertEquals(new BigDecimal("0.0000"), inventory.get("reservedQty"));
        assertEquals(new BigDecimal("5.0000"), inventory.get("availableQty"));
        assertEquals(1L, ((Number) inventory.get("distinctItemCount")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> workflows = (Map<String, Object>) summary.get("workflowDocumentsByStatus");
        assertTrue(workflows.containsKey("inboundReceipts"));
        assertTrue(workflows.containsKey("putawayTasks"));
        assertTrue(workflows.containsKey("outboundShipments"));
        assertTrue(workflows.containsKey("interWarehouseTransfers"));
        assertTrue(workflows.containsKey("replenishmentTasks"));
        assertTrue(workflows.containsKey("stockCounts"));
        assertTrue(workflows.containsKey("inventoryAdjustments"));

        Map<String, Object> otherSummary = reportingService.operationalSummary(otherTenant.principal());
        @SuppressWarnings("unchecked")
        Map<String, Object> otherInventory = (Map<String, Object>) otherSummary.get("inventoryPosition");
        assertEquals(new BigDecimal("9.0000"), otherInventory.get("onHandQty"));
        assertEquals(ledgerBefore, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id IN (?, ?)",
                fixture.tenantId(), otherTenant.tenantId()));

        jdbc.update("""
                DELETE rp FROM role_permissions rp
                JOIN membership_roles mr ON mr.role_id = rp.role_id AND mr.tenant_id = ?
                JOIN permissions p ON p.id = rp.permission_id
                WHERE mr.membership_id = ? AND p.permission_code = 'WMS_OPERATIONAL_REPORT_READ'
                """, fixture.tenantId(), fixture.membershipId());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> reportingService.operationalSummary(fixture.principal()));
    }

    @Test
    void inventoryPostingWaitsForConcurrentPermissionRevocationBeforeResolvingAccess() throws Exception {
        post(fixture, UUID.randomUUID(), "RECEIPT",
                leg(fixture, null, null, "AVAILABLE", "5.0000"));
        long ledgerBefore = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId());

        CountDownLatch revocationLockedTenant = new CountDownLatch(1);
        CountDownLatch releaseRevocation = new CountDownLatch(1);
        CompletableFuture<Void> revocation = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM tenants WHERE id = ? FOR UPDATE",
                            Long.class, fixture.tenantId());
                    jdbc.update("""
                            DELETE rp FROM role_permissions rp
                            JOIN membership_roles mr ON mr.role_id = rp.role_id AND mr.tenant_id = ?
                            WHERE mr.membership_id = ?
                              AND rp.permission_id = (
                                SELECT id FROM permissions WHERE permission_code = 'WMS_INVENTORY_POST'
                              )
                            """, fixture.tenantId(), fixture.membershipId());
                    revocationLockedTenant.countDown();
                    try {
                        if (!releaseRevocation.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Permission revocation barrier timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Permission revocation was interrupted", interrupted);
                    }
                }));

        assertTrue(revocationLockedTenant.await(10, TimeUnit.SECONDS),
                "Permission revocation must hold the tenant authorization lock");

        CountDownLatch postingStarted = new CountDownLatch(1);
        CompletableFuture<Boolean> posting = CompletableFuture.supplyAsync(() -> {
            postingStarted.countDown();
            try {
                postingService.post(fixture.principal(), new WmsInventoryPostingService.PostingCommand(
                        UUID.randomUUID(), "ISSUE",
                        List.of(leg(fixture, null, null, "AVAILABLE", "1.0000")),
                        "permission revocation race"));
                return true;
            } catch (org.springframework.security.access.AccessDeniedException revoked) {
                return false;
            }
        });

        assertTrue(postingStarted.await(10, TimeUnit.SECONDS), "Posting worker must start");
        assertFalse(posting.isDone(), "Posting must not pass the tenant authorization lock");
        releaseRevocation.countDown();
        revocation.get(20, TimeUnit.SECONDS);

        assertFalse(posting.get(20, TimeUnit.SECONDS),
                "Posting must re-resolve permissions after the revocation transaction commits");
        assertEquals(new BigDecimal("5.0000"), balance(fixture));
        assertEquals(ledgerBefore,
                count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
    }

    @Test
    void mysqlDeadlockVictimIsClassifiedAndRolledBackWithoutAutomaticRetry() throws Exception {
        Fixture second = fixture("NONE");
        String firstMarker = "DEADLOCK_WINNER_" + UUID.randomUUID();
        String secondMarker = "DEADLOCK_WINNER_" + UUID.randomUUID();
        CountDownLatch bothFirstLocks = new CountDownLatch(2);
        CountDownLatch acquireSecondLocks = new CountDownLatch(1);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() ->
                runOppositeTenantLockTransaction(fixture.tenantId(), second.tenantId(),
                        bothFirstLocks, acquireSecondLocks, firstMarker));
        CompletableFuture<String> secondWorker = CompletableFuture.supplyAsync(() ->
                runOppositeTenantLockTransaction(second.tenantId(), fixture.tenantId(),
                        bothFirstLocks, acquireSecondLocks, secondMarker));

        assertTrue(bothFirstLocks.await(10, TimeUnit.SECONDS),
                "Both MySQL transactions must hold their first tenant row lock");
        acquireSecondLocks.countDown();

        String firstOutcome = first.get(20, TimeUnit.SECONDS);
        String secondOutcome = secondWorker.get(20, TimeUnit.SECONDS);
        assertNotEquals(firstOutcome.startsWith("COMMITTED"), secondOutcome.startsWith("COMMITTED"),
                "One transaction must commit and the deadlock victim must roll back");
        assertTrue(firstOutcome.equals("DEADLOCK_1213_40001")
                        || secondOutcome.equals("DEADLOCK_1213_40001"),
                "MySQL deadlock must be surfaced as vendor error 1213 / SQLState 40001");

        String committedMarker = firstOutcome.startsWith("COMMITTED")
                ? firstMarker : secondMarker;
        assertEquals(2, count("""
                SELECT COUNT(*) FROM tenants
                WHERE id IN (?, ?) AND display_name = ?
                """, fixture.tenantId(), second.tenantId(), committedMarker),
                "Only the winning transaction may update both tenant rows");
    }

    @Test
    void mysqlLockWaitTimeoutIsClassifiedAndRolledBackWithoutAutomaticRetry() throws Exception {
        try (java.sql.Connection blocker = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            blocker.setAutoCommit(false);
            lockTenantRow(blocker, fixture.tenantId());

            CountDownLatch waiterReady = new CountDownLatch(1);
            CompletableFuture<String> waiter = CompletableFuture.supplyAsync(() -> {
                try (java.sql.Connection waiting = java.sql.DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
                    waiting.setAutoCommit(false);
                    try (java.sql.Statement statement = waiting.createStatement()) {
                        statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
                    }
                    waiterReady.countDown();
                    try {
                        lockTenantRow(waiting, fixture.tenantId());
                        waiting.commit();
                        return "ACQUIRED";
                    } catch (java.sql.SQLException failure) {
                        waiting.rollback();
                        if (failure.getErrorCode() == 1205 && "40001".equals(failure.getSQLState())) {
                            return "LOCK_TIMEOUT_1205_40001";
                        }
                        throw new IllegalStateException("Unexpected MySQL lock-wait failure", failure);
                    }
                } catch (java.sql.SQLException failure) {
                    throw new IllegalStateException("Could not execute lock-wait test transaction", failure);
                }
            });

            assertTrue(waiterReady.await(10, TimeUnit.SECONDS),
                    "The waiting transaction must be ready while the blocker holds the tenant row");
            assertEquals("LOCK_TIMEOUT_1205_40001", waiter.get(5, TimeUnit.SECONDS),
                    "MySQL lock wait timeout must preserve vendor code 1205 and Connector/J SQLState 40001");
            blocker.rollback();
            assertEquals("Inventory Test", jdbc.queryForObject(
                    "SELECT display_name FROM tenants WHERE id = ?", String.class, fixture.tenantId()),
                    "The timed-out transaction must not leave a partial write");
        }
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
    void auditFailureAfterInventoryLedgerWritesRollsBackTheEntirePosting() {
        post(fixture, UUID.randomUUID(), "RECEIPT",
                leg(fixture, null, null, "AVAILABLE", "5.0000"));

        String destinationLocation = createLocation(fixture);
        long beforeLedger = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId());
        long beforeAudit = count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ?",
                fixture.tenantId());

        UUID rejectedRequestId = UUID.fromString("00000000-0000-4000-8000-000000000117");
        jdbc.execute("""
                ALTER TABLE tenant_audit_events
                ADD CONSTRAINT chk_test_reject_inventory_audit
                CHECK (
                    event_type <> 'INVENTORY_MOVEMENT_POSTED'
                    OR JSON_UNQUOTE(JSON_EXTRACT(change_payload, '$.requestId'))
                       <> '00000000-0000-4000-8000-000000000117'
                )
                """);
        try {
            assertThrows(RuntimeException.class, () -> postingService.post(fixture.principal(),
                    new WmsInventoryPostingService.PostingCommand(rejectedRequestId, "TRANSFER",
                            List.of(
                                    leg(fixture, null, null, "AVAILABLE", "2.0000"),
                                    new WmsInventoryPostingService.PostingLeg(
                                            fixture.warehousePublicId(), destinationLocation,
                                            fixture.itemPublicId(), null, null, null,
                                            "AVAILABLE", new BigDecimal("2.0000"))),
                            "audit rollback test")));
        } finally {
            jdbc.execute("ALTER TABLE tenant_audit_events DROP CHECK chk_test_reject_inventory_audit");
        }

        assertEquals(new BigDecimal("5.0000"),
                bucketBalance(fixture, fixture.locationPublicId(), "AVAILABLE"));
        assertEquals(new BigDecimal("0.0000"),
                bucketBalance(fixture, destinationLocation, "AVAILABLE"));
        assertEquals(beforeLedger,
                count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(beforeAudit,
                count("SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ?", fixture.tenantId()));
    }

    @Test
    void oppositeDirectionTransfersCompleteWithoutDeadlockOrPartialMovement() throws Exception {
        String secondLocation = createLocation(fixture);
        post(fixture, UUID.randomUUID(), "RECEIPT",
                leg(fixture, null, null, "AVAILABLE", "10.0000"));
        WmsInventoryPostingService.PostingLeg secondReceipt =
                new WmsInventoryPostingService.PostingLeg(fixture.warehousePublicId(), secondLocation,
                        fixture.itemPublicId(), null, null, null, "AVAILABLE", new BigDecimal("10.0000"));
        postingService.post(fixture.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "RECEIPT", List.of(secondReceipt), "seed reverse transfer"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Boolean> forward = CompletableFuture.supplyAsync(() -> {
            awaitRequestStart(ready, start);
            return transferSucceeds(fixture, fixture.locationPublicId(), secondLocation);
        });
        CompletableFuture<Boolean> reverse = CompletableFuture.supplyAsync(() -> {
            awaitRequestStart(ready, start);
            return transferSucceeds(fixture, secondLocation, fixture.locationPublicId());
        });

        assertTrue(ready.await(10, TimeUnit.SECONDS), "Both transfer workers must reach the start barrier");
        start.countDown();
        assertTrue(forward.get(30, TimeUnit.SECONDS), "Forward transfer must complete");
        assertTrue(reverse.get(30, TimeUnit.SECONDS), "Reverse transfer must complete");

        assertEquals(new BigDecimal("10.0000"),
                bucketBalance(fixture, fixture.locationPublicId(), "AVAILABLE"));
        assertEquals(new BigDecimal("10.0000"),
                bucketBalance(fixture, secondLocation, "AVAILABLE"));
        assertEquals(6, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(4, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND movement_type = 'TRANSFER'
                """, fixture.tenantId()));
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
                leg(serialFixture, serialPublicId, null, "AVAILABLE", "1.0000"));
        assertEquals("SHIPPED", serialStatus(serialFixture, serialPublicId));
        assertEquals(BigDecimal.ZERO.setScale(4), balance(serialFixture));

        post(serialFixture, UUID.randomUUID(), "RETURN",
                leg(serialFixture, serialPublicId, null, "QUARANTINED", "1.0000"));
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

        assertThrows(RuntimeException.class, () -> post(serialFixture, UUID.randomUUID(), "ADJUSTMENT_OUT",
                leg(serialFixture, serialPublicId, null, "AVAILABLE", "1.0000")));
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
        boolean firstSucceeded = a.get(30, TimeUnit.SECONDS);
        boolean secondSucceeded = b.get(30, TimeUnit.SECONDS);
        assertNotEquals(firstSucceeded, secondSucceeded);
        assertEquals(new BigDecimal("1.0000"), balance(fixture));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
    }

    @Test
    void simultaneousReservationsCannotExceedAvailableInventory() throws Exception {
        post(fixture, UUID.randomUUID(), "RECEIPT", leg(fixture, null, null, "AVAILABLE", "5.0000"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> {
            awaitStart(ready, start);
            return reserveSucceeds(fixture, "4.0000");
        });
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> {
            awaitStart(ready, start);
            return reserveSucceeds(fixture, "4.0000");
        });

        assertTrue(ready.await(10, TimeUnit.SECONDS), "Both reservation workers must reach the start barrier");
        start.countDown();
        boolean firstSucceeded = first.get(30, TimeUnit.SECONDS);
        boolean secondSucceeded = second.get(30, TimeUnit.SECONDS);

        assertNotEquals(firstSucceeded, secondSucceeded);
        assertEquals(new BigDecimal("5.0000"), balance(fixture));
        assertEquals(new BigDecimal("4.0000"), reservedBalance(fixture));
        assertEquals(new BigDecimal("1.0000"), availableBalance(fixture));
    }

    @Test
    void issueRacingReservationCannotOversellTheSameInventoryBucket() throws Exception {
        post(fixture, UUID.randomUUID(), "RECEIPT", leg(fixture, null, null, "AVAILABLE", "5.0000"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Boolean> issue = CompletableFuture.supplyAsync(() -> {
            awaitStart(ready, start);
            return issueSucceeds(fixture, UUID.randomUUID());
        });
        CompletableFuture<Boolean> reservation = CompletableFuture.supplyAsync(() -> {
            awaitStart(ready, start);
            return reserveSucceeds(fixture, "4.0000");
        });

        assertTrue(ready.await(10, TimeUnit.SECONDS), "Both inventory workers must reach the start barrier");
        start.countDown();
        boolean issueSucceeded = issue.get(30, TimeUnit.SECONDS);
        boolean reservationSucceeded = reservation.get(30, TimeUnit.SECONDS);

        assertNotEquals(issueSucceeded, reservationSucceeded);
        BigDecimal onHand = balance(fixture);
        BigDecimal reserved = reservedBalance(fixture);
        assertTrue(onHand.signum() >= 0, "On-hand inventory must not become negative");
        assertTrue(reserved.signum() >= 0, "Reserved inventory must not become negative");
        assertTrue(onHand.compareTo(reserved) >= 0, "Reserved inventory must not exceed on-hand inventory");
        assertEquals(new BigDecimal("1.0000"), onHand.subtract(reserved));
        int ledgerEntries = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId());
        assertTrue(ledgerEntries == 1 || ledgerEntries == 2,
                "Only the receipt and, at most, one issue may be posted");
    }

    @Test
    void simultaneousTransferDispatchAndIssueCannotMoveTheSameSerialTwice() throws Exception {
        Fixture serialFixture = fixture("SERIAL");
        String serialNumber = "SN-DISPATCH-RACE-" + UUID.randomUUID();
        post(serialFixture, UUID.randomUUID(), "RECEIPT",
                leg(serialFixture, null, serialNumber, "AVAILABLE", "1.0000"));
        String serialPublicId = jdbc.queryForObject(
                "SELECT public_id FROM inventory_serials WHERE tenant_id = ? AND serial_number = ?",
                String.class, serialFixture.tenantId(), serialNumber);
        TransferFixture transfer = createReleasedSerialTransfer(serialFixture, serialPublicId);

        boolean[] outcomes = race(
                () -> dispatchSerialSucceeds(serialFixture, transfer, serialPublicId),
                () -> issueSerialSucceeds(serialFixture, serialPublicId));

        assertNotEquals(outcomes[0], outcomes[1],
                "Exactly one of transfer dispatch or issue may move the serial");
        assertEquals(outcomes[0] ? "IN_TRANSIT" : "SHIPPED", serialStatus(serialFixture, serialPublicId));
        assertEquals(BigDecimal.ZERO.setScale(4), balance(serialFixture));
        assertEquals(outcomes[0] ? "IN_TRANSIT" : "RELEASED",
                transferStatus(serialFixture, transfer.transferPublicId()));
        assertEquals(outcomes[0] ? "IN_TRANSIT" : "PLANNED",
                transferLineSerialStatus(serialFixture, transfer.allocationPublicId(), serialPublicId));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?",
                serialFixture.tenantId()));
    }

    @Test
    void simultaneousTransferReceiptAndShortageReconciliationResolveTheSameSerialOnlyOnce() throws Exception {
        Fixture serialFixture = fixture("SERIAL");
        String serialNumber = "SN-RECEIPT-RACE-" + UUID.randomUUID();
        post(serialFixture, UUID.randomUUID(), "RECEIPT",
                leg(serialFixture, null, serialNumber, "AVAILABLE", "1.0000"));
        String serialPublicId = jdbc.queryForObject(
                "SELECT public_id FROM inventory_serials WHERE tenant_id = ? AND serial_number = ?",
                String.class, serialFixture.tenantId(), serialNumber);
        TransferFixture transfer = createReleasedSerialTransfer(serialFixture, serialPublicId);
        transferService.dispatch(serialFixture.principal(), transfer.transferPublicId(),
                new WmsInterWarehouseTransferService.DispatchCommand(UUID.randomUUID(), List.of(
                        new WmsInterWarehouseTransferService.DispatchAllocation(
                                transfer.allocationPublicId(), BigDecimal.ONE, List.of(serialPublicId))), "dispatch before receipt race"));

        boolean[] outcomes = race(
                () -> receiveSerialSucceeds(serialFixture, transfer, serialPublicId),
                () -> reconcileSerialAsShortageSucceeds(serialFixture, transfer, serialPublicId));

        assertNotEquals(outcomes[0], outcomes[1],
                "Exactly one of receipt or shortage reconciliation may resolve the in-transit serial");
        if (outcomes[0]) {
            assertEquals("AVAILABLE", serialStatus(serialFixture, serialPublicId));
            assertEquals(new BigDecimal("1.0000"),
                    bucketBalance(serialFixture, transfer.destinationLocationPublicId(), "AVAILABLE"));
            assertEquals("RECEIVED",
                    transferLineSerialStatus(serialFixture, transfer.allocationPublicId(), serialPublicId));
            assertEquals(3, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?",
                    serialFixture.tenantId()));
        } else {
            assertEquals("LOST", serialStatus(serialFixture, serialPublicId));
            assertEquals(BigDecimal.ZERO.setScale(4),
                    bucketBalance(serialFixture, transfer.destinationLocationPublicId(), "AVAILABLE"));
            assertEquals("LOST",
                    transferLineSerialStatus(serialFixture, transfer.allocationPublicId(), serialPublicId));
            assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?",
                    serialFixture.tenantId()));
        }
        assertEquals("RECEIVED", transferStatus(serialFixture, transfer.transferPublicId()));
    }

    @Test
    void rejectsCrossTenantWarehouseReference() {
        Fixture otherTenant = fixture("NONE");
        WmsInventoryPostingService.PostingLeg crossTenantLeg = new WmsInventoryPostingService.PostingLeg(
                otherTenant.warehousePublicId(), otherTenant.locationPublicId(), fixture.itemPublicId(),
                null, null, null, "AVAILABLE", new BigDecimal("1.0000"));
        assertThrows(com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException.class, () -> postingService.post(fixture.principal(),
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
    private boolean reserveSucceeds(Fixture f, String quantity) {
        try {
            reservationService.reserve(f.principal(), new WmsInventoryReservationService.ReservationCommand(
                    f.warehousePublicId(), f.locationPublicId(), f.itemPublicId(), null,
                    new BigDecimal(quantity), List.of()));
            return true;
        } catch (RuntimeException expected) {
            return false;
        }
    }

    private static void awaitStart(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent inventory test start barrier timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent inventory test was interrupted", interrupted);
        }
    }

    private BigDecimal reservedBalance(Fixture f) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_qty), 0.0000) FROM inventory_balances
                WHERE tenant_id = ? AND item_id = (SELECT id FROM items WHERE public_id = ?)
                """, BigDecimal.class, f.tenantId(), f.itemPublicId());
    }

    private BigDecimal availableBalance(Fixture f) {
        return balance(f).subtract(reservedBalance(f));
    }

    private boolean issueSucceeds(Fixture f, UUID requestId) {
        try {
            post(f, requestId, "ISSUE", leg(f, null, null, "AVAILABLE", "4.0000"));
            return true;
        } catch (RuntimeException expected) {
            return false;
        }
    }

    @Test
    void simultaneousDuplicateRequestIdsApplyInventoryMovementOnlyOnce() throws Exception {
        post(fixture, UUID.randomUUID(), "RECEIPT", leg(fixture, null, null, "AVAILABLE", "5.0000"));
        UUID requestId = UUID.randomUUID();
        WmsInventoryPostingService.PostingLeg movement =
                leg(fixture, null, null, "AVAILABLE", "2.0000");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> {
            awaitRequestStart(ready, start);
            return postRequestSucceeds(fixture, requestId, movement);
        });
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> {
            awaitRequestStart(ready, start);
            return postRequestSucceeds(fixture, requestId, movement);
        });

        assertTrue(ready.await(10, TimeUnit.SECONDS), "Both duplicate-request workers must reach the barrier");
        start.countDown();
        boolean firstSucceeded = first.get(30, TimeUnit.SECONDS);
        boolean secondSucceeded = second.get(30, TimeUnit.SECONDS);

        assertNotEquals(firstSucceeded, secondSucceeded,
                "A tenant-scoped request ID must produce at most one successful posting");
        assertEquals(new BigDecimal("3.0000"), balance(fixture));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND request_id = ?
                """, fixture.tenantId(), requestId.toString()));
    }

    @Test
    void reusingInventoryRequestIdWithDifferentPayloadCannotApplySecondMovement() {
        UUID requestId = UUID.randomUUID();
        post(fixture, requestId, "RECEIPT", leg(fixture, null, null, "AVAILABLE", "5.0000"));

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> post(fixture, requestId, "ISSUE",
                        leg(fixture, null, null, "AVAILABLE", "2.0000")));

        assertEquals("The inventory request ID has already been posted", conflict.getMessage());
        assertEquals(new BigDecimal("5.0000"), balance(fixture));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND request_id = ?
                """, fixture.tenantId(), requestId.toString()));
    }

    @Test
    void failedInventoryRequestCanBeRetriedAfterItsPreconditionIsCorrected() {
        UUID requestId = UUID.randomUUID();
        WmsInventoryPostingService.PostingLeg issue =
                leg(fixture, null, null, "AVAILABLE", "2.0000");

        assertThrows(IllegalStateException.class, () -> post(fixture, requestId, "ISSUE", issue));
        assertEquals(BigDecimal.ZERO.setScale(4), balance(fixture));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND request_id = ?
                """, fixture.tenantId(), requestId.toString()));

        post(fixture, UUID.randomUUID(), "RECEIPT",
                leg(fixture, null, null, "AVAILABLE", "3.0000"));
        post(fixture, requestId, "ISSUE", issue);

        assertEquals(new BigDecimal("1.0000"), balance(fixture));
        assertEquals(1, count("""
                SELECT COUNT(*) FROM inventory_ledger
                WHERE tenant_id = ? AND request_id = ?
                """, fixture.tenantId(), requestId.toString()));
    }

    private boolean transferSucceeds(Fixture f, String sourceLocation, String destinationLocation) {
        try {
            WmsInventoryPostingService.PostingLeg source = new WmsInventoryPostingService.PostingLeg(
                    f.warehousePublicId(), sourceLocation, f.itemPublicId(), null, null, null,
                    "AVAILABLE", new BigDecimal("2.0000"));
            WmsInventoryPostingService.PostingLeg destination = new WmsInventoryPostingService.PostingLeg(
                    f.warehousePublicId(), destinationLocation, f.itemPublicId(), null, null, null,
                    "AVAILABLE", new BigDecimal("2.0000"));
            postingService.post(f.principal(), new WmsInventoryPostingService.PostingCommand(
                    UUID.randomUUID(), "TRANSFER", List.of(source, destination), "opposite direction concurrency test"));
            return true;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Opposite-direction transfer failed", failure);
        }
    }

    private boolean postRequestSucceeds(Fixture f, UUID requestId,
                                        WmsInventoryPostingService.PostingLeg movement) {
        try {
            post(f, requestId, "ISSUE", movement);
            return true;
        } catch (IllegalStateException duplicate) {
            if (!"The inventory request ID has already been posted".equals(duplicate.getMessage())) {
                throw duplicate;
            }
            return false;
        }
    }

    private void awaitRequestStart(CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Duplicate-request start barrier timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Duplicate-request worker was interrupted", interrupted);
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

    private TransferFixture createReleasedSerialTransfer(Fixture f, String serialPublicId) {
        String destinationWarehousePublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, 'Transfer Destination Warehouse', 'ACTIVE')
                """, destinationWarehousePublicId, f.tenantId(),
                "WH_DST_" + UUID.randomUUID().toString().substring(0, 8));
        long destinationWarehouseId = jdbc.queryForObject(
                "SELECT id FROM warehouses WHERE public_id = ?", Long.class, destinationWarehousePublicId);
        String destinationLocationPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, 'Transfer Destination Bin', 'BIN', 'ACTIVE')
                """, destinationLocationPublicId, f.tenantId(), destinationWarehouseId,
                "BIN_DST_" + UUID.randomUUID().toString().substring(0, 8));

        Map<String, Object> created = transferService.create(f.principal(),
                new WmsInterWarehouseTransferService.TransferCommand(UUID.randomUUID(),
                        f.warehousePublicId(), destinationWarehousePublicId,
                        "TR-" + UUID.randomUUID().toString().substring(0, 12),
                        null, "STANDARD", null, "serial concurrency integration test",
                        List.of(new WmsInterWarehouseTransferService.LineCommand(
                                f.itemPublicId(), BigDecimal.ONE, null,
                                List.of(new WmsInterWarehouseTransferService.AllocationCommand(
                                        f.locationPublicId(), destinationLocationPublicId, null,
                                        BigDecimal.ONE, List.of(serialPublicId)))))));
        String transferPublicId = Objects.toString(created.get("public_id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> line = (Map<String, Object>) ((List<?>) created.get("lines")).getFirst();
        @SuppressWarnings("unchecked")
        Map<String, Object> allocation = (Map<String, Object>) ((List<?>) line.get("allocations")).getFirst();
        String allocationPublicId = Objects.toString(allocation.get("public_id"));
        transferService.release(f.principal(), transferPublicId, UUID.randomUUID());
        return new TransferFixture(transferPublicId, allocationPublicId,
                destinationWarehousePublicId, destinationLocationPublicId);
    }

    private boolean dispatchSerialSucceeds(Fixture f, TransferFixture transfer, String serialPublicId) {
        try {
            transferService.dispatch(f.principal(), transfer.transferPublicId(),
                    new WmsInterWarehouseTransferService.DispatchCommand(UUID.randomUUID(), List.of(
                            new WmsInterWarehouseTransferService.DispatchAllocation(
                                    transfer.allocationPublicId(), BigDecimal.ONE, List.of(serialPublicId))), "dispatch race"));
            return true;
        } catch (IllegalStateException | IllegalArgumentException expectedConflict) {
            return false;
        }
    }

    private boolean issueSerialSucceeds(Fixture f, String serialPublicId) {
        try {
            post(f, UUID.randomUUID(), "ISSUE", leg(f, serialPublicId, null, "AVAILABLE", "1.0000"));
            return true;
        } catch (IllegalStateException | IllegalArgumentException expectedConflict) {
            return false;
        }
    }

    private boolean receiveSerialSucceeds(Fixture f, TransferFixture transfer, String serialPublicId) {
        try {
            transferService.receive(f.principal(), transfer.transferPublicId(),
                    new WmsInterWarehouseTransferService.ReceiveCommand(UUID.randomUUID(), List.of(
                            new WmsInterWarehouseTransferService.ReceiveAllocation(
                                    transfer.allocationPublicId(), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                                    List.of(serialPublicId), List.of(), List.of())), "good receipt race"));
            return true;
        } catch (IllegalStateException | IllegalArgumentException expectedConflict) {
            return false;
        }
    }

    private boolean reconcileSerialAsShortageSucceeds(Fixture f, TransferFixture transfer, String serialPublicId) {
        try {
            transferService.receive(f.principal(), transfer.transferPublicId(),
                    new WmsInterWarehouseTransferService.ReceiveCommand(UUID.randomUUID(), List.of(
                            new WmsInterWarehouseTransferService.ReceiveAllocation(
                                    transfer.allocationPublicId(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
                                    List.of(), List.of(), List.of(serialPublicId))), "shortage reconciliation race"));
            return true;
        } catch (IllegalStateException | IllegalArgumentException expectedConflict) {
            return false;
        }
    }

    private boolean[] race(java.util.function.BooleanSupplier firstAction,
                           java.util.function.BooleanSupplier secondAction) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> {
            awaitStart(ready, start);
            return firstAction.getAsBoolean();
        });
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> {
            awaitStart(ready, start);
            return secondAction.getAsBoolean();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "Both transfer workers must reach the start barrier");
        start.countDown();
        return new boolean[]{first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)};
    }

    private String transferStatus(Fixture f, String transferPublicId) {
        return jdbc.queryForObject("SELECT status FROM stock_transfers WHERE tenant_id = ? AND public_id = ?",
                String.class, f.tenantId(), transferPublicId);
    }

    private String transferLineSerialStatus(Fixture f, String allocationPublicId, String serialPublicId) {
        return jdbc.queryForObject("""
                SELECT ts.status FROM stock_transfer_line_serials ts
                JOIN stock_transfer_allocations a ON a.id = ts.stock_transfer_allocation_id
                    AND a.tenant_id = ts.tenant_id
                JOIN inventory_serials s ON s.id = ts.serial_id AND s.tenant_id = ts.tenant_id
                WHERE ts.tenant_id = ? AND a.public_id = ? AND s.public_id = ?
                """, String.class, f.tenantId(), allocationPublicId, serialPublicId);
    }

    private String runOppositeTenantLockTransaction(
            long firstTenantId, long secondTenantId,
            CountDownLatch bothFirstLocks, CountDownLatch acquireSecondLocks, String marker) {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.setAutoCommit(false);
            try {
                lockTenantRow(connection, firstTenantId);
                bothFirstLocks.countDown();
                if (!acquireSecondLocks.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Deadlock test barrier timed out");
                }
                lockTenantRow(connection, secondTenantId);
                try (java.sql.PreparedStatement update = connection.prepareStatement(
                        "UPDATE tenants SET display_name = ? WHERE id IN (?, ?)")) {
                    update.setString(1, marker);
                    update.setLong(2, firstTenantId);
                    update.setLong(3, secondTenantId);
                    assertEquals(2, update.executeUpdate());
                }
                connection.commit();
                return "COMMITTED:" + marker;
            } catch (java.sql.SQLException failure) {
                connection.rollback();
                if (failure.getErrorCode() == 1213 && "40001".equals(failure.getSQLState())) {
                    return "DEADLOCK_1213_40001";
                }
                throw new IllegalStateException("Unexpected SQL failure in deadlock test", failure);
            } catch (InterruptedException interrupted) {
                connection.rollback();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Deadlock test worker was interrupted", interrupted);
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Could not execute deadlock test transaction", failure);
        }
    }

    private void lockTenantRow(java.sql.Connection connection, long tenantId) throws java.sql.SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM tenants WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, tenantId);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new java.sql.SQLException("Tenant row was not found");
            }
        }
    }

    @Test
    void receiptCannotExceedConfiguredLocationWeightCapacity() {
        setItemWeight(fixture, "2.000000", "KG");
        setLocationWeightCapacity(fixture, "10.0000", "KG");

        assertThrows(IllegalStateException.class, () -> post(
                fixture, UUID.randomUUID(), "RECEIPT",
                leg(fixture, null, null, "AVAILABLE", "6.0000")));

        assertEquals(new BigDecimal("0.0000"), balance(fixture));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
    }

    @Test
    void locationWeightCapacityConvertsMassUnitsAndAllowsExactCapacityOnly() {
        setItemWeight(fixture, "500.000000", "G");
        setLocationWeightCapacity(fixture, "1.0000", "KG");

        post(fixture, UUID.randomUUID(), "RECEIPT",
                leg(fixture, null, null, "AVAILABLE", "2.0000"));
        assertEquals(new BigDecimal("2.0000"), balance(fixture));

        assertThrows(IllegalStateException.class, () -> post(
                fixture, UUID.randomUUID(), "RECEIPT",
                leg(fixture, null, null, "AVAILABLE", "1.0000")));

        assertEquals(new BigDecimal("2.0000"), balance(fixture));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
    }

    @Test
    void capacityControlledLocationRejectsInboundStockWhenItemWeightIsMissing() {
        setLocationWeightCapacity(fixture, "10.0000", "KG");

        assertThrows(IllegalStateException.class, () -> post(
                fixture, UUID.randomUUID(), "RECEIPT",
                leg(fixture, null, null, "AVAILABLE", "1.0000")));

        assertEquals(new BigDecimal("0.0000"), balance(fixture));
        assertEquals(0, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
    }

    @Test
    void capacityControlledLocationRejectsAdditionalStockWhenExistingStockHasNoWeight() {
        post(fixture, UUID.randomUUID(), "RECEIPT",
                leg(fixture, null, null, "AVAILABLE", "1.0000"));

        String secondItemPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO items
                  (public_id, tenant_id, sku, item_name, base_uom_id, weight_per_base_uom,
                   weight_uom_id, tracking_method, status)
                VALUES (?, ?, ?, 'Weighted Capacity Test Item',
                        (SELECT id FROM units_of_measure WHERE uom_code = 'EA'),
                        1.000000, (SELECT id FROM units_of_measure WHERE uom_code = 'KG'),
                        'NONE', 'ACTIVE')
                """, secondItemPublicId, fixture.tenantId(),
                "SKU_" + UUID.randomUUID().toString().substring(0, 8));
        setLocationWeightCapacity(fixture, "10.0000", "KG");

        WmsInventoryPostingService.PostingLeg secondItemLeg = new WmsInventoryPostingService.PostingLeg(
                fixture.warehousePublicId(), fixture.locationPublicId(), secondItemPublicId,
                null, null, null, "AVAILABLE", new BigDecimal("1.0000"));

        assertThrows(IllegalStateException.class, () -> postingService.post(
                fixture.principal(), new WmsInventoryPostingService.PostingCommand(
                        UUID.randomUUID(), "RECEIPT", List.of(secondItemLeg), "capacity integration test")));

        assertEquals(new BigDecimal("1.0000"), balance(fixture));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
        assertEquals(new BigDecimal("0.0000"), jdbc.queryForObject("""
                SELECT COALESCE(SUM(b.on_hand_qty), 0.0000)
                FROM inventory_balances b JOIN items i ON i.id = b.item_id AND i.tenant_id = b.tenant_id
                WHERE b.tenant_id = ? AND i.public_id = ?
                """, BigDecimal.class, fixture.tenantId(), secondItemPublicId));
    }

    @Test
    void concurrentReceiptsCannotExceedLocationWeightCapacity() throws Exception {
        setItemWeight(fixture, "2.000000", "KG");
        setLocationWeightCapacity(fixture, "10.0000", "KG");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> attemptCapacityReceipt(
                ready, start, UUID.randomUUID()));
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> attemptCapacityReceipt(
                ready, start, UUID.randomUUID()));

        assertTrue(ready.await(10, TimeUnit.SECONDS), "Both inventory requests must reach the start barrier");
        start.countDown();
        int accepted = (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(20, TimeUnit.SECONDS) ? 1 : 0);

        assertEquals(1, accepted);
        assertEquals(new BigDecimal("3.0000"), balance(fixture));
        assertEquals(1, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id = ?", fixture.tenantId()));
    }

    private boolean attemptCapacityReceipt(CountDownLatch ready, CountDownLatch start, UUID requestId) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Capacity concurrency test start barrier timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Capacity concurrency test was interrupted", interrupted);
        }
        try {
            post(fixture, requestId, "RECEIPT",
                    leg(fixture, null, null, "AVAILABLE", "3.0000"));
            return true;
        } catch (IllegalStateException rejected) {
            if (rejected.getMessage() == null || !rejected.getMessage().contains("capacity")) {
                throw rejected;
            }
            return false;
        }
    }

    private void setItemWeight(Fixture f, String weight, String uomCode) {
        jdbc.update("""
                UPDATE items
                SET weight_per_base_uom = ?,
                    weight_uom_id = (SELECT id FROM units_of_measure WHERE uom_code = ?)
                WHERE tenant_id = ? AND public_id = ?
                """, new BigDecimal(weight), uomCode, f.tenantId(), f.itemPublicId());
    }

    private void setLocationWeightCapacity(Fixture f, String capacity, String uomCode) {
        jdbc.update("""
                UPDATE warehouse_locations
                SET max_weight_capacity = ?, weight_uom = ?
                WHERE tenant_id = ? AND public_id = ?
                """, new BigDecimal(capacity), uomCode, f.tenantId(), f.locationPublicId());
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
                SELECT ?, id FROM permissions
                WHERE permission_code IN ('WMS_INVENTORY_POST', 'WMS_INVENTORY_RESERVE', 'WMS_TRANSFER_READ',
                  'WMS_TRANSFER_CREATE', 'WMS_TRANSFER_RELEASE', 'WMS_TRANSFER_DISPATCH', 'WMS_TRANSFER_RECEIVE',
                  'WMS_OPERATIONAL_REPORT_READ')
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
                membershipId, membershipPublicId, Set.of("WMS_INVENTORY_POST", "WMS_INVENTORY_RESERVE",
                "WMS_TRANSFER_READ", "WMS_TRANSFER_CREATE", "WMS_TRANSFER_RELEASE",
                "WMS_TRANSFER_DISPATCH", "WMS_TRANSFER_RECEIVE", "WMS_OPERATIONAL_REPORT_READ"));
        return new Fixture(tenantId, tenantPublicId, userPublicId, membershipId, membershipPublicId,
                warehousePublicId, locationPublicId, itemPublicId, principal);
    }

    private record TransferFixture(String transferPublicId, String allocationPublicId,
                                   String destinationWarehousePublicId, String destinationLocationPublicId) {}

    private record Fixture(long tenantId, String tenantPublicId, String userPublicId, long membershipId,
                           String membershipPublicId, String warehousePublicId, String locationPublicId,
                           String itemPublicId, WmsAuthenticatedPrincipal principal) {}
}