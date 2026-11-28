package com.zirocraft.zirologistics.wms.security.mfa.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaReauthenticationGrantStoragePort;
import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaReauthenticationPurpose;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * MySQL 8.4 storage for V32 tenant MFA password reauthentication grants.
 *
 * Grant consumption and revocation are single conditional InnoDB UPDATE
 * statements, so concurrent consumers cannot both succeed.
 */
public final class MySqlWmsTenantMfaReauthenticationGrantStorageAdapter
        implements WmsTenantMfaReauthenticationGrantStoragePort {

    private static final Duration MAXIMUM_TTL = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;

    public MySqlWmsTenantMfaReauthenticationGrantStorageAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public void insert(
            UUID publicId,
            byte[] grantDigest,
            long userId,
            long tenantId,
            long membershipId,
            long credentialVersion,
            WmsTenantMfaReauthenticationPurpose purpose,
            Instant createdAt,
            Instant expiresAt) {
        Objects.requireNonNull(publicId, "publicId must not be null");
        requireDigest(grantDigest);
        requireIdentity(userId, tenantId, membershipId, credentialVersion);
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt) || expiresAt.isAfter(createdAt.plus(MAXIMUM_TTL))) {
            throw new IllegalArgumentException("Tenant MFA reauthentication grant expiry must be within five minutes");
        }

        jdbc.update("""
                INSERT INTO wms_tenant_mfa_reauthentication_grants
                    (public_id, grant_digest, user_id, tenant_id, membership_id,
                     credential_version, purpose, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                publicId.toString(), grantDigest.clone(), userId, tenantId, membershipId,
                credentialVersion, purpose.name(), Timestamp.from(expiresAt), Timestamp.from(createdAt));
    }

    @Override
    public boolean consume(
            byte[] grantDigest,
            long userId,
            long tenantId,
            long membershipId,
            long credentialVersion,
            WmsTenantMfaReauthenticationPurpose purpose,
            Instant consumedAt) {
        requireDigest(grantDigest);
        requireIdentity(userId, tenantId, membershipId, credentialVersion);
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");

        return jdbc.update("""
                UPDATE wms_tenant_mfa_reauthentication_grants
                SET consumed_at = ?
                WHERE grant_digest = ?
                  AND user_id = ?
                  AND tenant_id = ?
                  AND membership_id = ?
                  AND credential_version = ?
                  AND purpose = ?
                  AND expires_at > ?
                  AND consumed_at IS NULL
                  AND revoked_at IS NULL
                """,
                Timestamp.from(consumedAt), grantDigest.clone(), userId, tenantId, membershipId,
                credentialVersion, purpose.name(), Timestamp.from(consumedAt)) == 1;
    }

    @Override
    public int revokeOutstandingForUser(long userId, Instant revokedAt) {
        if (userId < 1) throw new IllegalArgumentException("userId must be positive");
        Objects.requireNonNull(revokedAt, "revokedAt must not be null");
        return jdbc.update("""
                UPDATE wms_tenant_mfa_reauthentication_grants
                SET revoked_at = ?
                WHERE user_id = ?
                  AND consumed_at IS NULL
                  AND revoked_at IS NULL
                  AND expires_at > ?
                """, Timestamp.from(revokedAt), userId, Timestamp.from(revokedAt));
    }

    private static void requireDigest(byte[] digest) {
        Objects.requireNonNull(digest, "grantDigest must not be null");
        if (digest.length != 32) {
            throw new IllegalArgumentException("Tenant MFA reauthentication grant digest must be 32 bytes");
        }
    }

    private static void requireIdentity(
            long userId, long tenantId, long membershipId, long credentialVersion) {
        if (userId < 1 || tenantId < 1 || membershipId < 1 || credentialVersion < 0) {
            throw new IllegalArgumentException("Tenant MFA reauthentication identity binding is invalid");
        }
    }
}
