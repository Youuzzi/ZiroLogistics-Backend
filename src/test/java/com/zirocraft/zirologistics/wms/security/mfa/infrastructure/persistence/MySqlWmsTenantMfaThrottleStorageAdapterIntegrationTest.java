package com.zirocraft.zirologistics.wms.security.mfa.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaThrottleBucketKey;
import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaThrottleBucketType;
import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaThrottleDecision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MySqlWmsTenantMfaThrottleStorageAdapterIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wms_tenant_mfa_throttle_test")
            .withUsername("wms_tenant_mfa_throttle_test")
            .withPassword("wms_tenant_mfa_throttle_test");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static MySqlWmsTenantMfaThrottleStorageAdapter adapter;

    @BeforeAll
    static void createSchema() throws Exception {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE wms_tenant_mfa_throttle_buckets (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        bucket_type VARCHAR(20) NOT NULL,
                        key_version INT UNSIGNED NOT NULL,
                        bucket_key_hash BINARY(32) NOT NULL,
                        window_started_at DATETIME(6) NOT NULL,
                        attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
                        blocked_until DATETIME(6) NULL,
                        created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                        updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                            ON UPDATE CURRENT_TIMESTAMP(6),
                        UNIQUE KEY uk_window (bucket_type, key_version, bucket_key_hash, window_started_at),
                        CONSTRAINT chk_bucket_type CHECK (bucket_type IN ('ACCOUNT', 'FACTOR', 'SOURCE')),
                        CONSTRAINT chk_attempts CHECK (attempt_count <= 1000000)
                    ) ENGINE=InnoDB
                    """);
        }
        adapter = new MySqlWmsTenantMfaThrottleStorageAdapter(
                jdbc, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void blocksAtThresholdAndNeverShortensAnExistingBlock() {
        Instant now = Instant.parse("2026-11-27T08:00:00Z");
        Instant window = Instant.parse("2026-11-27T08:00:00Z");
        Instant firstBlock = now.plusSeconds(120);
        Instant laterBlock = now.plusSeconds(300);
        var key = key(WmsTenantMfaThrottleBucketType.ACCOUNT, "01");

        WmsTenantMfaThrottleDecision first =
                adapter.recordAttempt(key, window, 2, firstBlock, now);
        assertEquals(1, first.attemptCount());
        assertNull(first.blockedUntil());

        WmsTenantMfaThrottleDecision second =
                adapter.recordAttempt(key, window, 2, firstBlock, now);
        assertEquals(2, second.attemptCount());
        assertEquals(firstBlock, second.blockedUntil());
        assertTrue(second.blockedAt(now));

        WmsTenantMfaThrottleDecision third =
                adapter.recordAttempt(key, window, 2, laterBlock, now);
        assertEquals(3, third.attemptCount());
        assertEquals(laterBlock, third.blockedUntil());
    }

    @Test
    void concurrentAttemptsAreCountedWithoutLostUpdates() throws Exception {
        Instant now = Instant.parse("2026-11-27T08:00:00Z");
        var key = key(WmsTenantMfaThrottleBucketType.FACTOR, "02");
        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);

        try {
            List<Future<WmsTenantMfaThrottleDecision>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    return new MySqlWmsTenantMfaThrottleStorageAdapter(
                            new JdbcTemplate(dataSource),
                            new DataSourceTransactionManager(dataSource))
                            .recordAttempt(key, now, 100, now.plusSeconds(60), now);
                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (Future<WmsTenantMfaThrottleDecision> future : futures) {
                future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(12L, jdbc.queryForObject("""
                SELECT attempt_count FROM wms_tenant_mfa_throttle_buckets
                WHERE bucket_type = 'FACTOR' AND bucket_key_hash = UNHEX(?)
                """, Long.class, "02".repeat(32)));
    }

    @Test
    void bucketTypeKeyVersionAndWindowAreIsolated() {
        Instant now = Instant.parse("2026-11-27T08:00:00Z");
        var digest = "03".repeat(32);
        adapter.recordAttempt(new WmsTenantMfaThrottleBucketKey(
                WmsTenantMfaThrottleBucketType.ACCOUNT, 1, digest), now, 5, now.plusSeconds(60), now);
        adapter.recordAttempt(new WmsTenantMfaThrottleBucketKey(
                WmsTenantMfaThrottleBucketType.SOURCE, 1, digest), now, 5, now.plusSeconds(60), now);
        adapter.recordAttempt(new WmsTenantMfaThrottleBucketKey(
                WmsTenantMfaThrottleBucketType.ACCOUNT, 2, digest), now, 5, now.plusSeconds(60), now);
        adapter.recordAttempt(new WmsTenantMfaThrottleBucketKey(
                WmsTenantMfaThrottleBucketType.ACCOUNT, 1, digest), now.plusSeconds(60),
                5, now.plusSeconds(120), now.plusSeconds(60));

        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_tenant_mfa_throttle_buckets WHERE bucket_key_hash = UNHEX(?)",
                Integer.class, digest));
    }

    @Test
    void validatesInputsAndRejectsMalformedDigests() {
        Instant now = Instant.parse("2026-11-27T08:00:00Z");
        assertThrows(IllegalArgumentException.class, () ->
                new WmsTenantMfaThrottleBucketKey(WmsTenantMfaThrottleBucketType.ACCOUNT, 0, "00".repeat(32)));
        assertThrows(IllegalArgumentException.class, () ->
                new WmsTenantMfaThrottleBucketKey(WmsTenantMfaThrottleBucketType.ACCOUNT, 1, "not-a-digest"));
        assertThrows(IllegalArgumentException.class, () ->
                adapter.recordAttempt(key(WmsTenantMfaThrottleBucketType.ACCOUNT, "04"),
                        now, 0, now.plusSeconds(30), now));
        assertThrows(IllegalArgumentException.class, () ->
                adapter.recordAttempt(key(WmsTenantMfaThrottleBucketType.ACCOUNT, "04"),
                        now, 1, now, now));
    }

    private static WmsTenantMfaThrottleBucketKey key(
            WmsTenantMfaThrottleBucketType type, String prefix) {
        return new WmsTenantMfaThrottleBucketKey(type, 1, prefix.repeat(32));
    }
}
