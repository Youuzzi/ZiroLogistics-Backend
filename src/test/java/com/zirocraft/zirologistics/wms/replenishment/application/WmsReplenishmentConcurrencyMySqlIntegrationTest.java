package com.zirocraft.zirologistics.wms.replenishment.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
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
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsReplenishmentConcurrencyMySqlIntegrationTest {
    private static final Set<String> EXPECTED_CONCURRENCY_STATE_CONFLICTS = Set.of(
            "Replenishment task line changed; retry the confirmation",
            "Inventory balance changed or available quantity is insufficient",
            "Insufficient on-hand inventory",
            "Insufficient available inventory",
            "This replenishment confirmation has already been reversed",
            "Confirmation reversal or request ID already exists"
    );
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_repl_conc_test")
            .withUsername("zirowms_repl_conc_test")
            .withPassword("zirowms_repl_conc_test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsReplenishmentTaskService taskService;
    @Autowired private WmsInventoryPostingService postingService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void simultaneousConfirmationsFromDifferentTasksCannotOversellSharedSourceBucket() throws Exception {
        Fixture f = fixture();
        receive(f, f.sourcePublicId(), "8.0000");
        String destinationTwo = createDestination(f, "DST_TWO");
        String taskOne = startManualTask(f, f.destinationPublicId(), "6.0000");
        String taskTwo = startManualTask(f, destinationTwo, "6.0000");

        List<Boolean> outcomes = race(
                () -> confirm(f, taskOne, "6.0000"),
                () -> confirm(f, taskTwo, "6.0000"));

        assertEquals(1, successes(outcomes), "Exactly one task confirmation may consume the shared source stock");
        assertEquals(new BigDecimal("2.0000"), onHand(f, f.sourcePublicId()));
        assertEquals(new BigDecimal("6.0000"),
                onHand(f, f.destinationPublicId()).add(onHand(f, destinationTwo)));
        assertEquals(1, count("SELECT COUNT(*) FROM replenishment_confirmations WHERE tenant_id=? AND confirmation_type='MOVE'",
                f.tenantId()));
        assertEquals(2, count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=? AND movement_type='TRANSFER'",
                f.tenantId()));
        assertTrue(nonNegativeInventory(f));
    }

    @Test
    void simultaneousConfirmationsAgainstSameLineCannotExceedPlannedQuantity() throws Exception {
        Fixture f = fixture();
        receive(f, f.sourcePublicId(), "12.0000");
        String task = startManualTask(f, f.destinationPublicId(), "8.0000");

        List<Boolean> outcomes = race(
                () -> confirm(f, task, "6.0000"),
                () -> confirm(f, task, "6.0000"));

        assertEquals(1, successes(outcomes), "Only one confirmation can fit within the eight-unit plan");
        assertEquals(new BigDecimal("6.0000"), confirmedQuantity(f, task));
        assertEquals(new BigDecimal("6.0000"), onHand(f, f.sourcePublicId()));
        assertEquals(new BigDecimal("6.0000"), onHand(f, f.destinationPublicId()));
        assertEquals(1, count("SELECT COUNT(*) FROM replenishment_confirmations WHERE tenant_id=? AND confirmation_type='MOVE'",
                f.tenantId()));
        assertTrue(nonNegativeInventory(f));
    }

    @Test
    void simultaneousReversalRequestsApplyOriginalConfirmationAtMostOnce() throws Exception {
        Fixture f = fixture();
        receive(f, f.sourcePublicId(), "10.0000");
        String task = startManualTask(f, f.destinationPublicId(), "8.0000");
        confirm(f, task, "4.0000");
        String confirmationPublicId = jdbc.queryForObject("""
                SELECT public_id FROM replenishment_confirmations
                WHERE tenant_id=? AND confirmation_type='MOVE'
                """, String.class, f.tenantId());

        List<Boolean> outcomes = race(
                () -> reverse(f, confirmationPublicId),
                () -> reverse(f, confirmationPublicId));

        assertEquals(1, successes(outcomes), "Exactly one reversal may win for a confirmation");
        assertEquals(1, count("""
                SELECT COUNT(*) FROM replenishment_confirmations
                WHERE tenant_id=? AND confirmation_type='REVERSAL'
                  AND reverses_confirmation_id=(
                    SELECT id FROM replenishment_confirmations
                    WHERE tenant_id=? AND public_id=?
                  )
                """, f.tenantId(), f.tenantId(), confirmationPublicId));
        assertEquals(new BigDecimal("10.0000"), onHand(f, f.sourcePublicId()));
        assertEquals(new BigDecimal("0.0000"), onHand(f, f.destinationPublicId()));
        assertEquals(BigDecimal.ZERO.setScale(4), confirmedQuantity(f, task));
        assertTrue(nonNegativeInventory(f));
    }

    @Test
    void duplicateReplenishmentConfirmationRequestCannotMoveStockTwice() {
        Fixture f = fixture();
        receive(f, f.sourcePublicId(), "10.0000");
        String task = startManualTask(f, f.destinationPublicId(), "8.0000");
        UUID requestId = UUID.randomUUID();

        confirmWithRequestId(f, task, requestId, "3.0000");
        BigDecimal sourceAfterFirst = onHand(f, f.sourcePublicId());
        BigDecimal destinationAfterFirst = onHand(f, f.destinationPublicId());
        int ledgerAfterFirst = count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?", f.tenantId());
        int confirmationsAfterFirst = count("""
                SELECT COUNT(*) FROM replenishment_confirmations
                WHERE tenant_id=? AND confirmation_type='MOVE'
                """, f.tenantId());

        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> confirmWithRequestId(f, task, requestId, "2.0000"));

        assertEquals("The inventory request ID has already been posted", duplicate.getMessage());
        assertEquals(sourceAfterFirst, onHand(f, f.sourcePublicId()));
        assertEquals(destinationAfterFirst, onHand(f, f.destinationPublicId()));
        assertEquals(ledgerAfterFirst,
                count("SELECT COUNT(*) FROM inventory_ledger WHERE tenant_id=?", f.tenantId()));
        assertEquals(confirmationsAfterFirst, count("""
                SELECT COUNT(*) FROM replenishment_confirmations
                WHERE tenant_id=? AND confirmation_type='MOVE'
                """, f.tenantId()));
        assertEquals(new BigDecimal("3.0000"), confirmedQuantity(f, task));
        assertTrue(nonNegativeInventory(f));
    }

    private void confirmWithRequestId(Fixture f, String taskPublicId, UUID requestId, String quantity) {
        taskService.confirm(f.principal(), taskPublicId,
                new WmsReplenishmentTaskService.ConfirmCommand(
                        requestId, 1, new BigDecimal(quantity), List.of(), "Idempotency retry test"));
    }

    private void confirm(Fixture f, String taskPublicId, String quantity) {
        taskService.confirm(f.principal(), taskPublicId,
                new WmsReplenishmentTaskService.ConfirmCommand(
                        UUID.randomUUID(), 1, new BigDecimal(quantity), List.of(), "Concurrent confirmation test"));
    }

    private void reverse(Fixture f, String confirmationPublicId) {
        taskService.reverse(f.principal(), confirmationPublicId,
                new WmsReplenishmentTaskService.ReverseCommand(UUID.randomUUID(), "Concurrent reversal test"));
    }

    private String startManualTask(Fixture f, String destinationPublicId, String quantity) {
        Map<String, Object> task = taskService.createManual(f.principal(),
                new WmsReplenishmentTaskService.ManualTaskCommand(
                        f.warehousePublicId(), "NORMAL", "Concurrency task",
                        List.of(new WmsReplenishmentTaskService.ManualTaskLine(
                                f.itemPublicId(), f.sourcePublicId(), destinationPublicId, null,
                                new BigDecimal(quantity), "Concurrency line"))));
        String publicId = (String) task.get("public_id");
        taskService.release(f.principal(), publicId, 0L);
        taskService.assign(f.principal(), publicId, 1L, f.principal().membershipPublicId());
        taskService.start(f.principal(), publicId, 2L);
        return publicId;
    }

    private List<Boolean> race(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> one = executor.submit(attempt(ready, start, first));
            Future<Boolean> two = executor.submit(attempt(ready, start, second));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both database workers must reach the start barrier");
            start.countDown();
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Callable<Boolean> attempt(CountDownLatch ready, CountDownLatch start, Runnable action) {
        return () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency start barrier timed out");
            try {
                action.run();
                return true;
            } catch (IllegalStateException | IllegalArgumentException expectedConcurrencyConflict) {
                String message = expectedConcurrencyConflict.getMessage();
                boolean expectedStateConflict = expectedConcurrencyConflict instanceof IllegalStateException
                        && message != null && EXPECTED_CONCURRENCY_STATE_CONFLICTS.contains(message);
                boolean expectedQuantityConflict = expectedConcurrencyConflict instanceof IllegalArgumentException
                        && "Confirmation quantity exceeds the remaining planned quantity".equals(message);
                if (!expectedStateConflict && !expectedQuantityConflict) throw expectedConcurrencyConflict;
                return false;
            }
        };
    }

    private long successes(List<Boolean> outcomes) {
        return outcomes.stream().filter(Boolean::booleanValue).count();
    }

    private BigDecimal confirmedQuantity(Fixture f, String taskPublicId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.confirmed_qty),0)
                FROM replenishment_task_lines l
                JOIN replenishment_tasks t ON t.id=l.replenishment_task_id AND t.tenant_id=l.tenant_id
                WHERE t.tenant_id=? AND t.public_id=?
                """, BigDecimal.class, f.tenantId(), taskPublicId);
    }

    private BigDecimal onHand(Fixture f, String locationPublicId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(b.on_hand_qty),0)
                FROM inventory_balances b
                WHERE b.tenant_id=? AND b.warehouse_id=? AND b.location_id=(
                    SELECT id FROM warehouse_locations WHERE tenant_id=? AND public_id=?
                ) AND b.item_id=? AND b.stock_status='AVAILABLE'
                """, BigDecimal.class, f.tenantId(), f.warehouseId(), f.tenantId(),
                locationPublicId, f.itemId());
    }

    private boolean nonNegativeInventory(Fixture f) {
        Integer invalid = jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_balances
                WHERE tenant_id=? AND (on_hand_qty<0 OR reserved_qty<0 OR available_qty<0)
                """, Integer.class, f.tenantId());
        return invalid != null && invalid == 0;
    }

    private int count(String sql, Object... args) {
        Integer result = jdbc.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private void receive(Fixture f, String locationPublicId, String quantity) {
        postingService.post(f.principal(), new WmsInventoryPostingService.PostingCommand(
                UUID.randomUUID(), "RECEIPT",
                List.of(new WmsInventoryPostingService.PostingLeg(
                        f.warehousePublicId(), locationPublicId, f.itemPublicId(),
                        null, null, null, "AVAILABLE", new BigDecimal(quantity))),
                "Replenishment concurrency fixture receipt"));
    }

    private String createDestination(Fixture f, String prefix) {
        String publicId = uuid();
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, 'Concurrent Replenishment Destination', 'PICKING', 'ACTIVE')
                """, publicId, f.tenantId(), f.warehouseId(),
                prefix + "_" + publicId.substring(0, 8));
        return publicId;
    }

    private Fixture fixture() {
        String tenantPublicId = uuid();
        String userPublicId = uuid();
        String membershipPublicId = uuid();
        String warehousePublicId = uuid();
        String sourcePublicId = uuid();
        String destinationPublicId = uuid();
        String itemPublicId = uuid();

        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Replenishment Concurrency Legal', 'Replenishment Concurrency', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, tenantPublicId, "RPLC_" + tenantPublicId.substring(0, 8));
        long tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE public_id=?", Long.class, tenantPublicId);
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-hash-not-used', 'Replenishment Concurrency User', 'ACTIVE')
                """, userPublicId, "replenishment-concurrency-" + userPublicId + "@example.test");
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
                VALUES (?, ?, ?, 'Replenishment Concurrency Role', 'Integration test role', 0, 'ACTIVE')
                """, rolePublicId, tenantId, "RPLC_ROLE_" + tenantPublicId.substring(0, 8));
        long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE public_id=?", Long.class, rolePublicId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenantId, membershipId, roleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE permission_code IN (
                  'WMS_INVENTORY_POST',
                  'WMS_REPLENISHMENT_TASK_CREATE',
                  'WMS_REPLENISHMENT_TASK_RELEASE','WMS_REPLENISHMENT_TASK_ASSIGN',
                  'WMS_REPLENISHMENT_TASK_START','WMS_REPLENISHMENT_TASK_CONFIRM',
                  'WMS_REPLENISHMENT_CONFIRMATION_REVERSE'
                )
                """, roleId);
        jdbc.update("""
                INSERT INTO warehouses (public_id, tenant_id, warehouse_code, warehouse_name, status)
                VALUES (?, ?, ?, 'Replenishment Concurrency Warehouse', 'ACTIVE')
                """, warehousePublicId, tenantId, "RPLC_WH_" + tenantPublicId.substring(0, 8));
        long warehouseId = jdbc.queryForObject("SELECT id FROM warehouses WHERE tenant_id=? AND public_id=?",
                Long.class, tenantId, warehousePublicId);
        jdbc.update("""
                INSERT INTO warehouse_locations
                  (public_id, tenant_id, warehouse_id, location_code, location_name, location_type, status)
                VALUES (?, ?, ?, ?, 'Concurrency Source', 'STORAGE', 'ACTIVE'),
                       (?, ?, ?, ?, 'Concurrency Destination', 'PICKING', 'ACTIVE')
                """, sourcePublicId, tenantId, warehouseId, "RPLC_SRC_" + tenantPublicId.substring(0, 8),
                destinationPublicId, tenantId, warehouseId, "RPLC_DST_" + tenantPublicId.substring(0, 8));
        long itemUomId = jdbc.queryForObject("SELECT id FROM units_of_measure WHERE uom_code='EA'", Long.class);
        jdbc.update("""
                INSERT INTO items (public_id, tenant_id, sku, item_name, base_uom_id, tracking_method,
                    is_stock_item, status)
                VALUES (?, ?, ?, 'Replenishment Concurrency Item', ?, 'NONE', 1, 'ACTIVE')
                """, itemPublicId, tenantId, "RPLC_SKU_" + tenantPublicId.substring(0, 8), itemUomId);
        long sourceId = jdbc.queryForObject("SELECT id FROM warehouse_locations WHERE tenant_id=? AND public_id=?",
                Long.class, tenantId, sourcePublicId);
        long destinationId = jdbc.queryForObject("SELECT id FROM warehouse_locations WHERE tenant_id=? AND public_id=?",
                Long.class, tenantId, destinationPublicId);
        long itemId = jdbc.queryForObject("SELECT id FROM items WHERE tenant_id=? AND public_id=?",
                Long.class, tenantId, itemPublicId);
        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                userPublicId, tenantId, tenantPublicId, membershipId, membershipPublicId, Set.of());
        return new Fixture(tenantId, warehouseId, warehousePublicId, sourceId, sourcePublicId,
                destinationId, destinationPublicId, itemId, itemPublicId, principal);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(long tenantId, long warehouseId, String warehousePublicId,
                           long sourceId, String sourcePublicId,
                           long destinationId, String destinationPublicId,
                           long itemId, String itemPublicId, WmsAuthenticatedPrincipal principal) {}
}
