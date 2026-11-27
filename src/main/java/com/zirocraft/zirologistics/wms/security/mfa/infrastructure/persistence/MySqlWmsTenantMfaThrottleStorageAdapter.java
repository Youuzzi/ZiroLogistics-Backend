package com.zirocraft.zirologistics.wms.security.mfa.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaThrottleBucketKey;
import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaThrottleDecision;
import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaThrottleStoragePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

/** MySQL 8.4 atomic fixed-window counter for the dedicated tenant MFA table. */
public final class MySqlWmsTenantMfaThrottleStorageAdapter
        implements WmsTenantMfaThrottleStoragePort {

    private static final String UPSERT = """
            INSERT INTO wms_tenant_mfa_throttle_buckets
                (bucket_type, key_version, bucket_key_hash, window_started_at,
                 attempt_count, blocked_until)
            VALUES (?, ?, ?, ?, 1,
                CASE WHEN ? <= 1 THEN ? ELSE NULL END)
            ON DUPLICATE KEY UPDATE
                blocked_until = CASE
                    WHEN attempt_count + 1 >= ?
                    THEN GREATEST(COALESCE(blocked_until, ?), ?)
                    ELSE blocked_until
                END,
                attempt_count = LEAST(attempt_count + 1, 1000000)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public MySqlWmsTenantMfaThrottleStorageAdapter(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    @Override
    public WmsTenantMfaThrottleDecision recordAttempt(
            WmsTenantMfaThrottleBucketKey key,
            Instant windowStartedAt,
            int threshold,
            Instant blockedUntil,
            Instant decisionTime) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(windowStartedAt, "windowStartedAt must not be null");
        Objects.requireNonNull(blockedUntil, "blockedUntil must not be null");
        Objects.requireNonNull(decisionTime, "decisionTime must not be null");
        if (threshold < 1 || threshold > 1_000_000) {
            throw new IllegalArgumentException("threshold must be between 1 and 1000000");
        }
        if (!blockedUntil.isAfter(decisionTime)) {
            throw new IllegalArgumentException("blockedUntil must be after decisionTime");
        }
        if (windowStartedAt.isAfter(decisionTime)) {
            throw new IllegalArgumentException("windowStartedAt must not be after decisionTime");
        }

        byte[] digest = HexFormat.of().parseHex(key.digestHex());
        LocalDateTime window = LocalDateTime.ofInstant(windowStartedAt, ZoneOffset.UTC);
        LocalDateTime block = LocalDateTime.ofInstant(blockedUntil, ZoneOffset.UTC);

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            jdbcTemplate.update(UPSERT,
                    key.type().name(), key.keyVersion(), digest, window,
                    threshold, block, threshold, block, block);

            return jdbcTemplate.queryForObject("""
                    SELECT attempt_count, blocked_until
                    FROM wms_tenant_mfa_throttle_buckets
                    WHERE bucket_type = ?
                      AND key_version = ?
                      AND bucket_key_hash = ?
                      AND window_started_at = ?
                    FOR UPDATE
                    """, (rs, row) -> {
                LocalDateTime storedBlock = rs.getObject("blocked_until", LocalDateTime.class);
                return new WmsTenantMfaThrottleDecision(
                        rs.getLong("attempt_count"),
                        storedBlock == null ? null : storedBlock.toInstant(ZoneOffset.UTC));
            }, key.type().name(), key.keyVersion(), digest, window);
        }), "Tenant MFA throttle transaction returned no result");
    }
}
