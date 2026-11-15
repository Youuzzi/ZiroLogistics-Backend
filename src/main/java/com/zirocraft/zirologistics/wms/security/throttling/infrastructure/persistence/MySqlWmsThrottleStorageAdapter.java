package com.zirocraft.zirologistics.wms.security.throttling.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.security.throttling.application.WmsThrottleAccountFailureResult;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsThrottlePreVerificationResult;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsThrottleStoragePort;
import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketKey;
import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/**
 * MySQL 8.4 implementation of the WMS login-throttle storage port.
 *
 * Every state-changing operation is executed in an explicit transaction.
 * The adapter stores only HMAC digests, never raw account or source identifiers.
 */
public final class MySqlWmsThrottleStorageAdapter implements WmsThrottleStoragePort {

    private static final String UPSERT_SOURCE = """
            INSERT INTO wms_login_throttle_buckets
                (bucket_type, key_version, bucket_key_hash, window_started_at, attempt_count)
            VALUES ('SOURCE', ?, ?, ?, 1)
            ON DUPLICATE KEY UPDATE attempt_count = attempt_count + 1
            """;

    private static final String UPSERT_ACCOUNT_FAILURE = """
            INSERT INTO wms_login_throttle_buckets
                (bucket_type, key_version, bucket_key_hash, window_started_at, attempt_count, blocked_until)
            VALUES ('ACCOUNT', ?, ?, ?, 1,
                CASE WHEN ? <= 1 THEN ? ELSE NULL END)
            ON DUPLICATE KEY UPDATE
                blocked_until = CASE
                    WHEN attempt_count + 1 >= ?
                    THEN GREATEST(COALESCE(blocked_until, ?), ?)
                    ELSE blocked_until
                END,
                attempt_count = attempt_count + 1
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public MySqlWmsThrottleStorageAdapter(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    @Override
    public WmsThrottlePreVerificationResult recordSourceAttemptAndCheckAccountCooldown(
            WmsThrottleBucketKey sourceKey,
            WmsThrottleBucketKey accountKey,
            Instant sourceWindowStartedAt,
            Instant decisionTime) {
        requireKeyType(sourceKey, WmsThrottleBucketType.SOURCE, "sourceKey");
        requireKeyType(accountKey, WmsThrottleBucketType.ACCOUNT, "accountKey");
        Objects.requireNonNull(sourceWindowStartedAt, "sourceWindowStartedAt must not be null");
        Objects.requireNonNull(decisionTime, "decisionTime must not be null");

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            jdbcTemplate.update(UPSERT_SOURCE,
                    sourceKey.keyVersion(),
                    digestBytes(sourceKey),
                    utcDateTime(sourceWindowStartedAt));

            Long sourceCount = jdbcTemplate.queryForObject("""
                    SELECT attempt_count
                    FROM wms_login_throttle_buckets
                    WHERE bucket_type = 'SOURCE'
                      AND key_version = ?
                      AND bucket_key_hash = ?
                      AND window_started_at = ?
                    FOR UPDATE
                    """, Long.class,
                    sourceKey.keyVersion(),
                    digestBytes(sourceKey),
                    utcDateTime(sourceWindowStartedAt));

            LocalDateTime blockedUntil = jdbcTemplate.queryForObject("""
                    SELECT MAX(blocked_until)
                    FROM wms_login_throttle_buckets
                    WHERE bucket_type = 'ACCOUNT'
                      AND key_version = ?
                      AND bucket_key_hash = ?
                      AND blocked_until > ?
                    """, LocalDateTime.class,
                    accountKey.keyVersion(),
                    digestBytes(accountKey),
                    utcDateTime(decisionTime));

            return new WmsThrottlePreVerificationResult(
                    Objects.requireNonNull(sourceCount, "source bucket count was not returned"),
                    blockedUntil == null ? null : blockedUntil.toInstant(ZoneOffset.UTC));
        }), "Throttle pre-verification transaction returned no result");
    }

    @Override
    public WmsThrottleAccountFailureResult recordFailedAccountAttempt(
            WmsThrottleBucketKey accountKey,
            Instant windowStartedAt,
            int failureThreshold,
            Instant cooldownUntil) {
        requireKeyType(accountKey, WmsThrottleBucketType.ACCOUNT, "accountKey");
        Objects.requireNonNull(windowStartedAt, "windowStartedAt must not be null");
        Objects.requireNonNull(cooldownUntil, "cooldownUntil must not be null");
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            byte[] digest = digestBytes(accountKey);
            LocalDateTime windowTimestamp = utcDateTime(windowStartedAt);
            LocalDateTime cooldownTimestamp = utcDateTime(cooldownUntil);

            jdbcTemplate.update(UPSERT_ACCOUNT_FAILURE,
                    accountKey.keyVersion(),
                    digest,
                    windowTimestamp,
                    failureThreshold,
                    cooldownTimestamp,
                    failureThreshold,
                    cooldownTimestamp,
                    cooldownTimestamp);

            return jdbcTemplate.queryForObject("""
                    SELECT attempt_count, blocked_until
                    FROM wms_login_throttle_buckets
                    WHERE bucket_type = 'ACCOUNT'
                      AND key_version = ?
                      AND bucket_key_hash = ?
                      AND window_started_at = ?
                    FOR UPDATE
                    """, (resultSet, rowNumber) -> {
                LocalDateTime blockedUntil = resultSet.getObject("blocked_until", LocalDateTime.class);
                return new WmsThrottleAccountFailureResult(
                        resultSet.getLong("attempt_count"),
                        blockedUntil == null ? null : blockedUntil.toInstant(ZoneOffset.UTC));
            }, accountKey.keyVersion(), digest, windowTimestamp);
        }), "Throttle account failure transaction returned no result");
    }

    @Override
    public int deleteExpiredBuckets(
            Instant retentionCutoff,
            Instant decisionTime,
            int batchSize) {
        Objects.requireNonNull(retentionCutoff, "retentionCutoff must not be null");
        Objects.requireNonNull(decisionTime, "decisionTime must not be null");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }

        return Objects.requireNonNull(transactionTemplate.execute(status ->
                jdbcTemplate.update("""
                        DELETE FROM wms_login_throttle_buckets
                        WHERE window_started_at < ?
                          AND (blocked_until IS NULL OR blocked_until <= ?)
                        ORDER BY window_started_at
                        LIMIT ?
                        """, utcDateTime(retentionCutoff), utcDateTime(decisionTime), batchSize)),
                "Throttle cleanup transaction returned no result");
    }

    private static void requireKeyType(
            WmsThrottleBucketKey key,
            WmsThrottleBucketType expected,
            String argumentName) {
        Objects.requireNonNull(key, argumentName + " must not be null");
        if (key.type() != expected) {
            throw new IllegalArgumentException(argumentName + " must have bucket type " + expected);
        }
    }

    private static byte[] digestBytes(WmsThrottleBucketKey key) {
        return HexFormat.of().parseHex(key.digestHex());
    }

    private static LocalDateTime utcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
