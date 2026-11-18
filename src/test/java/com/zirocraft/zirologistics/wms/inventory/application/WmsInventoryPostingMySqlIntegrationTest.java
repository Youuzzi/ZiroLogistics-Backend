package com.zirocraft.zirologistics.wms.inventory.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.transfer.application.WmsInterWarehouseTransferService;
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
    @Autowired private WmsInventoryReservationService reservationService;
    @Autowired private WmsInterWarehouseTransferService transferService;
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
                  'WMS_TRANSFER_CREATE', 'WMS_TRANSFER_RELEASE', 'WMS_TRANSFER_DISPATCH', 'WMS_TRANSFER_RECEIVE')
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
                "WMS_TRANSFER_DISPATCH", "WMS_TRANSFER_RECEIVE"));
        return new Fixture(tenantId, tenantPublicId, userPublicId, membershipId, membershipPublicId,
                warehousePublicId, locationPublicId, itemPublicId, principal);
    }

    private record TransferFixture(String transferPublicId, String allocationPublicId,
                                   String destinationWarehousePublicId, String destinationLocationPublicId) {}

    private record Fixture(long tenantId, String tenantPublicId, String userPublicId, long membershipId,
                           String membershipPublicId, String warehousePublicId, String locationPublicId,
                           String itemPublicId, WmsAuthenticatedPrincipal principal) {}
}