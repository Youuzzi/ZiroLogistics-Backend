package com.zirocraft.zirologistics.wms.security.throttling.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.security.throttling.application.WmsThrottleAccountFailureResult;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsThrottlePreVerificationResult;
import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketKey;
import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MySqlWmsThrottleStorageAdapterIntegrationTest {

    private static final Instant WINDOW = Instant.parse("2026-11-16T00:00:00Z");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v2_throttle_adapter_test")
            .withUsername("wms_throttle_adapter_test")
            .withPassword("wms_throttle_adapter_test");

    private static MySqlWmsThrottleStorageAdapter adapter;
    private static JdbcTemplate jdbcTemplate;

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
                "throttle adapter tests must exercise a non-UTC MySQL session");
        adapter = new MySqlWmsThrottleStorageAdapter(
                jdbcTemplate, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void recordsSourceAttemptAndFindsAccountCooldownAcrossWindows() {
        WmsThrottleBucketKey source = key(WmsThrottleBucketType.SOURCE, "1");
        WmsThrottleBucketKey account = key(WmsThrottleBucketType.ACCOUNT, "2");
        Instant cooldown = WINDOW.plusSeconds(600);

        WmsThrottleAccountFailureResult failure = adapter.recordFailedAccountAttempt(
                account, WINDOW.minusSeconds(3600), 1, cooldown);
        assertEquals(1, failure.failureCount());
        assertEquals(cooldown, failure.blockedUntil());

        WmsThrottlePreVerificationResult precheck =
                adapter.recordSourceAttemptAndCheckAccountCooldown(
                        source, account, WINDOW, WINDOW);
        assertEquals(1, precheck.sourceAttemptCount());
        assertTrue(precheck.accountCooldownActiveAt(WINDOW));
        assertEquals(cooldown, precheck.accountBlockedUntil());
    }

    @Test
    void concurrentAccountFailuresAreNotLostAndCooldownIsSetAtThreshold() throws Exception {
        WmsThrottleBucketKey account = key(WmsThrottleBucketType.ACCOUNT, "3");
        int workers = 8;
        int attemptsPerWorker = 25;
        int threshold = workers * attemptsPerWorker;
        Instant cooldown = WINDOW.plusSeconds(900);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);

        try {
            List<Future<WmsThrottleAccountFailureResult>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test start timed out");
                    }
                    WmsThrottleAccountFailureResult result = null;
                    for (int i = 0; i < attemptsPerWorker; i++) {
                        result = adapter.recordFailedAccountAttempt(
                                account, WINDOW, threshold, cooldown);
                    }
                    return result;
                }));
            }

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();

            for (Future<WmsThrottleAccountFailureResult> future : futures) {
                assertNotNull(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        Integer count = jdbcTemplate.queryForObject("""
                SELECT attempt_count FROM wms_login_throttle_buckets
                WHERE bucket_type = 'ACCOUNT'
                  AND key_version = 1
                  AND bucket_key_hash = ?
                  AND window_started_at = ?
                """, Integer.class, digest("3"), LocalDateTime.ofInstant(WINDOW, ZoneOffset.UTC));
        assertEquals(threshold, count);

        Instant persistedCooldown = jdbcTemplate.queryForObject("""
                SELECT blocked_until FROM wms_login_throttle_buckets
                WHERE bucket_type = 'ACCOUNT'
                  AND key_version = 1
                  AND bucket_key_hash = ?
                  AND window_started_at = ?
                """, (resultSet, rowNumber) -> resultSet.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC),
                digest("3"), LocalDateTime.ofInstant(WINDOW, ZoneOffset.UTC));
        assertEquals(cooldown, persistedCooldown);
    }

    @Test
    void repeatedFailuresDoNotExtendCooldownAfterThresholdCrossing() {
        WmsThrottleBucketKey account = key(WmsThrottleBucketType.ACCOUNT, "9");
        Instant firstCooldown = WINDOW.plusSeconds(300);

        WmsThrottleAccountFailureResult first = adapter.recordFailedAccountAttempt(
                account, WINDOW, 3, firstCooldown);
        assertEquals(1, first.failureCount());
        assertNull(first.blockedUntil());

        WmsThrottleAccountFailureResult second = adapter.recordFailedAccountAttempt(
                account, WINDOW, 3, WINDOW.plusSeconds(600));
        assertEquals(2, second.failureCount());
        assertNull(second.blockedUntil());

        WmsThrottleAccountFailureResult threshold = adapter.recordFailedAccountAttempt(
                account, WINDOW, 3, firstCooldown);
        assertEquals(3, threshold.failureCount());
        assertEquals(firstCooldown, threshold.blockedUntil());

        WmsThrottleAccountFailureResult fourth = adapter.recordFailedAccountAttempt(
                account, WINDOW, 3, WINDOW.plusSeconds(3_600));
        assertEquals(4, fourth.failureCount());
        assertEquals(firstCooldown, fourth.blockedUntil());

        WmsThrottleAccountFailureResult fifth = adapter.recordFailedAccountAttempt(
                account, WINDOW, 3, WINDOW.plusSeconds(7_200));
        assertEquals(5, fifth.failureCount());
        assertEquals(firstCooldown, fifth.blockedUntil());
    }


    @Test
    void successfulAccountResetDeletesOnlyThatAccountsWindowsAndPreservesSourceHistory() {
        WmsThrottleBucketKey account = key(WmsThrottleBucketType.ACCOUNT, "reset-target");
        WmsThrottleBucketKey otherAccount = key(WmsThrottleBucketType.ACCOUNT, "reset-other");
        WmsThrottleBucketKey previousKeyVersion = key(WmsThrottleBucketType.ACCOUNT, 2, "reset-target");
        WmsThrottleBucketKey source = key(WmsThrottleBucketType.SOURCE, "reset-source");
        Instant olderWindow = WINDOW.minusSeconds(3_600);
        Instant activeCooldown = WINDOW.plusSeconds(900);

        adapter.recordFailedAccountAttempt(account, olderWindow, 1, WINDOW.plusSeconds(600));
        adapter.recordFailedAccountAttempt(account, WINDOW, 1, activeCooldown);
        adapter.recordFailedAccountAttempt(otherAccount, WINDOW, 1, activeCooldown);
        adapter.recordFailedAccountAttempt(previousKeyVersion, WINDOW, 1, activeCooldown);
        WmsThrottlePreVerificationResult beforeReset =
                adapter.recordSourceAttemptAndCheckAccountCooldown(source, account, WINDOW, WINDOW);
        assertTrue(beforeReset.accountCooldownActiveAt(WINDOW));
        assertEquals(1, beforeReset.sourceAttemptCount());

        int deleted = adapter.resetAccountFailures(account);
        assertEquals(2, deleted);

        Integer accountRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wms_login_throttle_buckets
                WHERE bucket_type = 'ACCOUNT'
                  AND key_version = ?
                  AND bucket_key_hash = ?
                """, Integer.class, account.keyVersion(), digest("reset-target"));
        assertEquals(0, accountRows);

        Integer otherAccountRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wms_login_throttle_buckets
                WHERE bucket_type = 'ACCOUNT'
                  AND key_version = ?
                  AND bucket_key_hash = ?
                """, Integer.class, otherAccount.keyVersion(), digest("reset-other"));
        assertEquals(1, otherAccountRows);

        Integer previousKeyVersionRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wms_login_throttle_buckets
                WHERE bucket_type = 'ACCOUNT'
                  AND key_version = ?
                  AND bucket_key_hash = ?
                """, Integer.class, previousKeyVersion.keyVersion(), digest("reset-target"));
        assertEquals(1, previousKeyVersionRows);

        Integer sourceRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wms_login_throttle_buckets
                WHERE bucket_type = 'SOURCE'
                  AND key_version = ?
                  AND bucket_key_hash = ?
                """, Integer.class, source.keyVersion(), digest("reset-source"));
        assertEquals(1, sourceRows);

        WmsThrottlePreVerificationResult afterReset =
                adapter.recordSourceAttemptAndCheckAccountCooldown(source, account, WINDOW, WINDOW);
        assertEquals(2, afterReset.sourceAttemptCount());
        assertEquals(0, adapter.resetAccountFailures(account), "reset must be idempotent when no rows remain");
        assertNull(afterReset.accountBlockedUntil());

        WmsThrottleAccountFailureResult nextFailure =
                adapter.recordFailedAccountAttempt(account, WINDOW, 3, activeCooldown);
        assertEquals(1, nextFailure.failureCount());
        assertNull(nextFailure.blockedUntil());
    }

    @Test
    void resetRejectsSourceBucketBeforeDatabaseMutation() {
        WmsThrottleBucketKey source = key(WmsThrottleBucketType.SOURCE, "reset-invalid");
        assertThrows(IllegalArgumentException.class, () -> adapter.resetAccountFailures(source));
    }

    @Test
    void cleanupIsBoundedAndRetainsActiveCooldowns() {
        WmsThrottleBucketKey expired = key(WmsThrottleBucketType.ACCOUNT, "4");
        WmsThrottleBucketKey active = key(WmsThrottleBucketType.ACCOUNT, "5");
        WmsThrottleBucketKey source = key(WmsThrottleBucketType.SOURCE, "6");
        Instant oldWindow = WINDOW.minusSeconds(259_200);
        Instant sourceWindow = oldWindow.plusSeconds(3_600);
        Instant activeWindow = oldWindow.plusSeconds(7_200);
        Instant activeCooldown = WINDOW.plusSeconds(300);

        adapter.recordFailedAccountAttempt(expired, oldWindow, 1, WINDOW.minusSeconds(1));
        adapter.recordFailedAccountAttempt(active, activeWindow, 1, activeCooldown);
        adapter.recordSourceAttemptAndCheckAccountCooldown(source, expired, sourceWindow, WINDOW);

        int deleted = adapter.deleteExpiredBuckets(WINDOW.minusSeconds(1), WINDOW, 1);
        assertEquals(1, deleted);

        Integer remainingActive = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wms_login_throttle_buckets
                WHERE bucket_type = 'ACCOUNT' AND bucket_key_hash = ?
                """, Integer.class, digest("5"));
        assertEquals(1, remainingActive);

        Integer remainingExpired = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM wms_login_throttle_buckets
                WHERE bucket_type = 'ACCOUNT' AND bucket_key_hash = ?
                """, Integer.class, digest("4"));
        assertEquals(0, remainingExpired);
    }

    @Test
    void rejectsWrongBucketTypesAndInvalidPolicyInputsBeforeDatabaseMutation() {
        WmsThrottleBucketKey account = key(WmsThrottleBucketType.ACCOUNT, "7");
        WmsThrottleBucketKey source = key(WmsThrottleBucketType.SOURCE, "8");

        assertThrows(IllegalArgumentException.class, () ->
                adapter.recordSourceAttemptAndCheckAccountCooldown(
                        account, source, WINDOW, WINDOW));
        assertThrows(IllegalArgumentException.class, () ->
                adapter.recordFailedAccountAttempt(account, WINDOW, 0, WINDOW.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class, () ->
                adapter.deleteExpiredBuckets(WINDOW, WINDOW, 0));
    }

    private static String nonUtcJdbcUrl() {
        return MYSQL.getJdbcUrl() + "?connectionTimeZone=%2B07:00&forceConnectionTimeZoneToSession=true";
    }

    private static WmsThrottleBucketKey key(WmsThrottleBucketType type, String suffix) {
        return key(type, 1, suffix);
    }

    private static WmsThrottleBucketKey key(WmsThrottleBucketType type, int keyVersion, String suffix) {
        return new WmsThrottleBucketKey(type, keyVersion, digestHex(suffix));
    }

    private static String digestHex(String suffix) {
        return java.util.HexFormat.of().formatHex(digest(suffix));
    }

    private static byte[] digest(String suffix) {
        byte[] bytes = new byte[32];
        byte[] source = suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(source, 0, bytes, 0, source.length);
        return bytes;
    }
}
