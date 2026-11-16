package com.zirocraft.zirologistics.wms.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable persisted view of a purpose-bound WMS identity action token.
 * The digest is always copied so callers cannot mutate persistence identity.
 */
public final class WmsIdentityActionTokenRecord {

    private final long id;
    private final byte[] tokenDigest;
    private final WmsIdentityActionTokenPurpose purpose;
    private final Long userId;
    private final Long tenantId;
    private final Long invitedByUserId;
    private final String targetEmail;
    private final Instant expiresAt;
    private final Instant consumedAt;
    private final Instant revokedAt;
    private final Instant createdAt;

    public WmsIdentityActionTokenRecord(
            long id,
            byte[] tokenDigest,
            WmsIdentityActionTokenPurpose purpose,
            Long userId,
            Long tenantId,
            Long invitedByUserId,
            String targetEmail,
            Instant expiresAt,
            Instant consumedAt,
            Instant revokedAt,
            Instant createdAt) {
        if (id < 1) {
            throw new IllegalArgumentException("id must be positive");
        }
        Objects.requireNonNull(tokenDigest, "tokenDigest must not be null");
        if (tokenDigest.length != 32) {
            throw new IllegalArgumentException("tokenDigest must contain 32 bytes");
        }
        this.id = id;
        this.tokenDigest = tokenDigest.clone();
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
        this.userId = userId;
        this.tenantId = tenantId;
        this.invitedByUserId = invitedByUserId;
        this.targetEmail = targetEmail;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.consumedAt = consumedAt;
        this.revokedAt = revokedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public long id() {
        return id;
    }

    public byte[] tokenDigest() {
        return tokenDigest.clone();
    }

    public WmsIdentityActionTokenPurpose purpose() {
        return purpose;
    }

    public Long userId() {
        return userId;
    }

    public Long tenantId() {
        return tenantId;
    }

    public Long invitedByUserId() {
        return invitedByUserId;
    }

    public String targetEmail() {
        return targetEmail;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant consumedAt() {
        return consumedAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean isUsableAt(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return consumedAt == null && revokedAt == null && expiresAt.isAfter(instant);
    }
}
