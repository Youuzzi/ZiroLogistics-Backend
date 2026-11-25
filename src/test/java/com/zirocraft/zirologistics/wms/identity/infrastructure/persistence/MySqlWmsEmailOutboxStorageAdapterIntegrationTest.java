package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.identity.domain.WmsEmailOutboxMessage;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEmailOutboxStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MySqlWmsEmailOutboxStorageAdapterIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-16T13:00:00Z");
    private static final SecureRandom RANDOM = new SecureRandom();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v4_email_outbox_adapter_test")
            .withUsername("wms_v4_outbox_adapter_test")
            .withPassword("wms_v4_outbox_adapter_test");

    private static JdbcTemplate jdbcTemplate;
    private static MySqlWmsEmailOutboxStorageAdapter adapter;

    @BeforeAll
    static void migrateDisposableDatabaseAndCreateAdapter() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .load()
                .migrate();

        DataSource dataSource = new DriverManagerDataSource(
                nonUtcJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        assertEquals("+07:00", jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class),
                "outbox adapter tests must exercise a non-UTC MySQL session");
        adapter = new MySqlWmsEmailOutboxStorageAdapter(
                jdbcTemplate, new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clearOutboxRowsBetweenTests() {
        jdbcTemplate.update("DELETE FROM wms_identity_action_email_outbox");
    }

    @Test
    void concurrentWorkersClaimDisjointBatchesUsingSkipLocked() throws Exception {
        List<UUID> queued = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID messageId = UUID.randomUUID();
            queued.add(messageId);
            enqueue(messageId, "worker-" + i);
        }

        int workers = 2;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<List<WmsEmailOutboxMessage>>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Outbox claim test start timed out");
                    }
                    return adapter.claimAvailable(NOW, 5);
                }));
            }

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();

            List<WmsEmailOutboxMessage> claimed = new ArrayList<>();
            for (Future<List<WmsEmailOutboxMessage>> future : futures) {
                claimed.addAll(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            // SKIP LOCKED may return a short batch when the competing worker
            // holds the first candidate rows; a subsequent poll must claim the rest.
            claimed.addAll(adapter.claimAvailable(NOW, 10));

            Set<UUID> claimedIds = new HashSet<>();
            for (WmsEmailOutboxMessage message : claimed) {
                assertTrue(claimedIds.add(message.messageId()), "A message must not be claimed twice");
                assertEquals(WmsEmailOutboxStatus.PROCESSING, message.status());
                assertEquals(1, message.attemptCount());
            }
            assertEquals(10, claimed.size());
            assertEquals(new HashSet<>(queued), claimedIds);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void attemptCountFencesStaleWorkersAfterLeaseRecovery() {
        UUID messageId = UUID.randomUUID();
        enqueue(messageId, "lease");
        WmsEmailOutboxMessage firstClaim = adapter.claimAvailable(NOW, 1).getFirst();

        assertEquals(1, adapter.recoverStaleProcessing(
                NOW.plusSeconds(1), NOW.plusSeconds(2), 3, 100));

        WmsEmailOutboxMessage secondClaim = adapter.claimAvailable(NOW.plusSeconds(2), 1).getFirst();
        assertEquals(2, secondClaim.attemptCount());

        assertEquals(0, adapter.markSent(messageId, firstClaim.attemptCount(), NOW.plusSeconds(3)),
                "a stale worker must not complete a recovered/reclaimed message");
        assertEquals(1, adapter.markSent(messageId, secondClaim.attemptCount(), NOW.plusSeconds(3)));
        assertEquals(0, adapter.markSent(messageId, secondClaim.attemptCount(), NOW.plusSeconds(4)));

        assertEquals("SENT", status(messageId));
    }

    @Test
    void retryReturnsToPendingAndExhaustedAttemptsMoveToDeadLetter() {
        UUID retryId = UUID.randomUUID();
        enqueue(retryId, "retry");
        WmsEmailOutboxMessage retryClaim = adapter.claimAvailable(NOW, 1).getFirst();

        assertEquals(1, adapter.scheduleRetry(
                retryId, retryClaim.attemptCount(), NOW.plusSeconds(60), "SMTP_TEMPORARY_FAILURE", 3));
        assertEquals("PENDING", status(retryId));

        WmsEmailOutboxMessage secondClaim = adapter.claimAvailable(NOW.plusSeconds(60), 1).getFirst();
        assertEquals(2, secondClaim.attemptCount());
        assertEquals(1, adapter.scheduleRetry(
                retryId, secondClaim.attemptCount(), NOW.plusSeconds(120), "SMTP_TEMPORARY_FAILURE", 2));
        assertEquals("DEAD_LETTER", status(retryId));
    }

    @Test
    void validatesOpaqueErrorCodesAndClaimLimitsBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> adapter.claimAvailable(NOW, 0));
        assertThrows(IllegalArgumentException.class, () -> adapter.claimAvailable(NOW, 101));
        assertThrows(IllegalArgumentException.class, () -> adapter.recoverStaleProcessing(
                NOW, NOW, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> adapter.scheduleRetry(
                UUID.randomUUID(), 1, NOW, "raw exception with PII", 3));
        assertThrows(IllegalArgumentException.class, () -> adapter.enqueue(
                UUID.randomUUID(), WmsIdentityActionTokenPurpose.INVITATION,
                " User@example.test ", ciphertext(), nonce(), 1, NOW));
        assertThrows(IllegalArgumentException.class, () -> adapter.enqueue(
                UUID.randomUUID(), WmsIdentityActionTokenPurpose.INVITATION,
                "user@example.test", new byte[16], nonce(), 1, NOW));
    }

    private static String nonUtcJdbcUrl() {
        return MYSQL.getJdbcUrl() + "?connectionTimeZone=%2B07:00&forceConnectionTimeZoneToSession=true";
    }

    private static void enqueue(UUID messageId, String suffix) {
        adapter.enqueue(messageId, WmsIdentityActionTokenPurpose.INVITATION,
                "outbox-" + suffix + "-" + UUID.randomUUID() + "@example.test",
                ciphertext(), nonce(), 1, NOW);
    }

    private static String status(UUID messageId) {
        return jdbcTemplate.queryForObject("""
                SELECT status
                FROM wms_identity_action_email_outbox
                WHERE message_id = ?
                """, String.class, uuidBytes(messageId));
    }

    private static byte[] ciphertext() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] nonce() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] uuidBytes(UUID value) {
        return java.nio.ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
