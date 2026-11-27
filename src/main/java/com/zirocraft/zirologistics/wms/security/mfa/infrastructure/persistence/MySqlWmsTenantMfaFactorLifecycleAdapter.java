package com.zirocraft.zirologistics.wms.security.mfa.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaFactorLifecyclePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * MySQL implementation of the pending/active/revoked tenant TOTP factor state machine.
 * No plaintext seed is accepted or persisted by this adapter.
 */
public final class MySqlWmsTenantMfaFactorLifecycleAdapter
        implements WmsTenantMfaFactorLifecyclePort {

    private static final String LOCK_ACTIVE_USER = """
            SELECT id FROM users
            WHERE public_id = ? AND status = 'ACTIVE'
            FOR UPDATE
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public MySqlWmsTenantMfaFactorLifecycleAdapter(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    @Override
    public long createPendingFactor(
            String userPublicId,
            UUID factorPublicId,
            String encryptionKeyVersion,
            byte[] nonce,
            byte[] encryptedSeed,
            Instant createdAt,
            Instant expiresAt) {
        requireUser(userPublicId);
        Objects.requireNonNull(factorPublicId, "factorPublicId must not be null");
        requireText(encryptionKeyVersion, "encryptionKeyVersion");
        Objects.requireNonNull(nonce, "nonce must not be null");
        Objects.requireNonNull(encryptedSeed, "encryptedSeed must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (nonce.length != 12 || encryptedSeed.length < 16 || encryptedSeed.length > 512) {
            throw new IllegalArgumentException("Encrypted TOTP seed material is invalid");
        }
        if (!expiresAt.isAfter(createdAt)
                || expiresAt.isAfter(createdAt.plus(10, ChronoUnit.MINUTES))) {
            throw new IllegalArgumentException("Pending factor expiry must be within ten minutes");
        }

        return transaction.execute(status -> {
            Long userId = jdbc.query(LOCK_ACTIVE_USER,
                    rs -> rs.next() ? rs.getLong(1) : null, userPublicId);
            if (userId == null) {
                throw new IllegalStateException("Active tenant user is required");
            }
            Integer existing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM wms_tenant_mfa_factors
                    WHERE user_id = ? AND status IN ('PENDING', 'ACTIVE', 'LOCKED')
                    """, Integer.class, userId);
            if (existing != null && existing > 0) {
                throw new IllegalStateException("A tenant MFA factor already exists for this user");
            }
            jdbc.update("""
                    INSERT INTO wms_tenant_mfa_factors
                        (public_id, user_id, factor_type, status, seed_nonce,
                         encrypted_seed, encryption_key_version, pending_expires_at,
                         created_at, updated_at)
                    VALUES (?, ?, 'TOTP', 'PENDING', ?, ?, ?, ?, ?, ?)
                    """,
                    factorPublicId.toString(), userId, nonce.clone(), encryptedSeed.clone(),
                    encryptionKeyVersion, Timestamp.from(expiresAt),
                    Timestamp.from(createdAt), Timestamp.from(createdAt));
            Long id = jdbc.queryForObject(
                    "SELECT id FROM wms_tenant_mfa_factors WHERE public_id = ?",
                    Long.class, factorPublicId.toString());
            if (id == null) throw new IllegalStateException("Pending factor insert was not visible");
            return id;
        });
    }

    @Override
    public boolean activatePendingFactor(
            String userPublicId, UUID factorPublicId, long matchedTimeStep, Instant activatedAt) {
        requireUser(userPublicId);
        Objects.requireNonNull(factorPublicId, "factorPublicId must not be null");
        Objects.requireNonNull(activatedAt, "activatedAt must not be null");
        if (matchedTimeStep < 0) throw new IllegalArgumentException("matchedTimeStep must not be negative");

        return transaction.execute(status -> {
            Long userId = jdbc.query(LOCK_ACTIVE_USER,
                    rs -> rs.next() ? rs.getLong(1) : null, userPublicId);
            if (userId == null) return false;
            return jdbc.update("""
                    UPDATE wms_tenant_mfa_factors
                    SET status = 'ACTIVE',
                        activated_at = ?,
                        pending_expires_at = NULL,
                        last_accepted_time_step = ?,
                        updated_at = ?
                    WHERE public_id = ?
                      AND user_id = ?
                      AND factor_type = 'TOTP'
                      AND status = 'PENDING'
                      AND pending_expires_at > ?
                    """,
                    Timestamp.from(activatedAt), matchedTimeStep, Timestamp.from(activatedAt),
                    factorPublicId.toString(), userId, Timestamp.from(activatedAt)) == 1;
        });
    }

    @Override
    public boolean revokeFactor(String userPublicId, UUID factorPublicId, Instant revokedAt) {
        requireUser(userPublicId);
        Objects.requireNonNull(factorPublicId, "factorPublicId must not be null");
        Objects.requireNonNull(revokedAt, "revokedAt must not be null");

        return transaction.execute(status -> {
            Long userId = jdbc.query(LOCK_ACTIVE_USER,
                    rs -> rs.next() ? rs.getLong(1) : null, userPublicId);
            if (userId == null) return false;
            return jdbc.update("""
                    UPDATE wms_tenant_mfa_factors
                    SET status = 'REVOKED',
                        seed_nonce = NULL,
                        encrypted_seed = NULL,
                        encryption_key_version = NULL,
                        pending_expires_at = NULL,
                        revoked_at = ?,
                        updated_at = ?
                    WHERE public_id = ?
                      AND user_id = ?
                      AND status IN ('PENDING', 'ACTIVE', 'LOCKED')
                    """,
                    Timestamp.from(revokedAt), Timestamp.from(revokedAt),
                    factorPublicId.toString(), userId) == 1;
        });
    }

    private static void requireUser(String value) {
        requireText(value, "userPublicId");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be canonical and non-blank");
        }
    }
}
