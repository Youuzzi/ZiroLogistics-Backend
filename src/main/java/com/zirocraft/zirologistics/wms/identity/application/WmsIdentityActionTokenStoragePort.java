package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenRecord;

import java.time.Instant;
import java.util.Optional;

/**
 * Application-owned persistence port for durable WMS identity action tokens.
 *
 * Locking and multi-step operations must be called inside one Spring-managed
 * transaction. Invitation operations acquire the tenant row before token rows;
 * global-user actions acquire the user row before token rows.
 */
public interface WmsIdentityActionTokenStoragePort {

    Optional<TenantStatus> lockTenantForInvitation(long tenantId);

    int revokeOutstandingInvitations(long tenantId, String normalizedEmail, Instant revokedAt);

    /**
     * Revokes outstanding tokens for one global user and purpose.
     * Caller must hold the corresponding user row lock in the same transaction.
     */
    int revokeOutstandingForUser(long userId, WmsIdentityActionTokenPurpose purpose, Instant revokedAt);

    long insert(
            byte[] tokenDigest,
            WmsIdentityActionTokenPurpose purpose,
            Long userId,
            Long tenantId,
            Long invitedByUserId,
            String normalizedTargetEmail,
            Instant expiresAt,
            Instant createdAt);

    /**
     * Reads a token without locking. Use only to discover a lock key, then
     * re-read with findByDigestForUpdate before any state transition.
     */
    Optional<WmsIdentityActionTokenRecord> findByDigest(
            byte[] tokenDigest,
            WmsIdentityActionTokenPurpose expectedPurpose);

    Optional<WmsIdentityActionTokenRecord> findByDigestForUpdate(
            byte[] tokenDigest,
            WmsIdentityActionTokenPurpose expectedPurpose);

    int markConsumed(long tokenId, Instant consumedAt);

    int markRevoked(long tokenId, Instant revokedAt);
}
