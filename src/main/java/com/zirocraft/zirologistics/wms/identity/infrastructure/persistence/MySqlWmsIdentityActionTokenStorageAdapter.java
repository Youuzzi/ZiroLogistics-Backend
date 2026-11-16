package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.identity.application.WmsEmailNormalizer;
import com.zirocraft.zirologistics.wms.identity.application.WmsIdentityActionTokenStoragePort;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MySQL implementation of the WMS identity action token persistence port.
 *
 * All multi-step lifecycle operations are transactionally composed by the
 * application service; SELECT FOR UPDATE locks are meaningful only within
 * that caller-owned transaction.
 */
@Repository
@Profile("wms")
public class MySqlWmsIdentityActionTokenStorageAdapter implements WmsIdentityActionTokenStoragePort {

    private static final RowMapper<WmsIdentityActionTokenRecord> TOKEN_ROW_MAPPER = (resultSet, rowNumber) ->
            new WmsIdentityActionTokenRecord(
                    resultSet.getLong("id"),
                    resultSet.getBytes("token_digest"),
                    WmsIdentityActionTokenPurpose.valueOf(resultSet.getString("purpose")),
                    nullableLong(resultSet, "user_id"),
                    nullableLong(resultSet, "tenant_id"),
                    nullableLong(resultSet, "invited_by_user_id"),
                    resultSet.getString("target_email"),
                    toInstant(resultSet.getObject("expires_at", LocalDateTime.class)),
                    toInstant(resultSet.getObject("consumed_at", LocalDateTime.class)),
                    toInstant(resultSet.getObject("revoked_at", LocalDateTime.class)),
                    toInstant(resultSet.getObject("created_at", LocalDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public MySqlWmsIdentityActionTokenStorageAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    public Optional<TenantStatus> lockTenantForInvitation(long tenantId) {
        if (tenantId < 1) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        List<TenantStatus> statuses = jdbcTemplate.query("""
                SELECT status
                FROM tenants
                WHERE id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> TenantStatus.valueOf(resultSet.getString("status")), tenantId);
        return statuses.stream().findFirst();
    }

    @Override
    public int revokeOutstandingInvitations(long tenantId, String normalizedEmail, Instant revokedAt) {
        if (tenantId < 1) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        requireCanonicalEmail(normalizedEmail);
        Objects.requireNonNull(revokedAt, "revokedAt must not be null");

        return jdbcTemplate.update("""
                UPDATE wms_identity_action_tokens
                SET revoked_at = ?
                WHERE tenant_id = ?
                  AND target_email = ?
                  AND purpose = 'INVITATION'
                  AND consumed_at IS NULL
                  AND revoked_at IS NULL
                  AND expires_at > ?
                """, utcDateTime(revokedAt), tenantId, normalizedEmail, utcDateTime(revokedAt));
    }

    @Override
    public long insert(
            byte[] tokenDigest,
            WmsIdentityActionTokenPurpose purpose,
            Long userId,
            Long tenantId,
            Long invitedByUserId,
            String normalizedTargetEmail,
            Instant expiresAt,
            Instant createdAt) {
        requireDigest(tokenDigest);
        Objects.requireNonNull(purpose, "purpose must not be null");
        requireCanonicalEmail(normalizedTargetEmail);
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be later than createdAt");
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO wms_identity_action_tokens
                        (token_digest, purpose, user_id, tenant_id, invited_by_user_id,
                         target_email, expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setBytes(1, tokenDigest.clone());
            statement.setString(2, purpose.name());
            setNullableLong(statement, 3, userId);
            setNullableLong(statement, 4, tenantId);
            setNullableLong(statement, 5, invitedByUserId);
            statement.setString(6, normalizedTargetEmail);
            statement.setObject(7, utcDateTime(expiresAt));
            statement.setObject(8, utcDateTime(createdAt));
            return statement;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null || key.longValue() < 1) {
            throw new IllegalStateException("WMS identity action token insert returned no generated ID");
        }
        return key.longValue();
    }

    @Override
    public Optional<WmsIdentityActionTokenRecord> findByDigest(
            byte[] tokenDigest,
            WmsIdentityActionTokenPurpose expectedPurpose) {
        requireDigest(tokenDigest);
        Objects.requireNonNull(expectedPurpose, "expectedPurpose must not be null");

        List<WmsIdentityActionTokenRecord> rows = jdbcTemplate.query("""
                SELECT id, token_digest, purpose, user_id, tenant_id, invited_by_user_id,
                       target_email, expires_at, consumed_at, revoked_at, created_at
                FROM wms_identity_action_tokens
                WHERE token_digest = ?
                  AND purpose = ?
                """, TOKEN_ROW_MAPPER, tokenDigest.clone(), expectedPurpose.name());
        return rows.stream().findFirst();
    }

    @Override
    public Optional<WmsIdentityActionTokenRecord> findByDigestForUpdate(
            byte[] tokenDigest,
            WmsIdentityActionTokenPurpose expectedPurpose) {
        requireDigest(tokenDigest);
        Objects.requireNonNull(expectedPurpose, "expectedPurpose must not be null");

        List<WmsIdentityActionTokenRecord> rows = jdbcTemplate.query("""
                SELECT id, token_digest, purpose, user_id, tenant_id, invited_by_user_id,
                       target_email, expires_at, consumed_at, revoked_at, created_at
                FROM wms_identity_action_tokens
                WHERE token_digest = ?
                  AND purpose = ?
                FOR UPDATE
                """, TOKEN_ROW_MAPPER, tokenDigest.clone(), expectedPurpose.name());
        return rows.stream().findFirst();
    }

    @Override
    public int markConsumed(long tokenId, Instant consumedAt) {
        requireTokenId(tokenId);
        Objects.requireNonNull(consumedAt, "consumedAt must not be null");

        return jdbcTemplate.update("""
                UPDATE wms_identity_action_tokens
                SET consumed_at = ?
                WHERE id = ?
                  AND consumed_at IS NULL
                  AND revoked_at IS NULL
                  AND expires_at > ?
                """, utcDateTime(consumedAt), tokenId, utcDateTime(consumedAt));
    }

    @Override
    public int markRevoked(long tokenId, Instant revokedAt) {
        requireTokenId(tokenId);
        Objects.requireNonNull(revokedAt, "revokedAt must not be null");

        return jdbcTemplate.update("""
                UPDATE wms_identity_action_tokens
                SET revoked_at = ?
                WHERE id = ?
                  AND consumed_at IS NULL
                  AND revoked_at IS NULL
                """, utcDateTime(revokedAt), tokenId);
    }

    private static void requireCanonicalEmail(String email) {
        String normalized = WmsEmailNormalizer.normalize(email);
        if (!normalized.equals(email)) {
            throw new IllegalArgumentException("target email must already be normalized");
        }
    }

    private static void requireDigest(byte[] digest) {
        Objects.requireNonNull(digest, "tokenDigest must not be null");
        if (digest.length != 32) {
            throw new IllegalArgumentException("tokenDigest must contain 32 bytes");
        }
    }

    private static void requireTokenId(long tokenId) {
        if (tokenId < 1) {
            throw new IllegalArgumentException("tokenId must be positive");
        }
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static LocalDateTime utcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
