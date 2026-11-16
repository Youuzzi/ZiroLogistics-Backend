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
 * transaction. Invitation issuance and acceptance acquire the tenant row
 * before locking or changing invitation-token rows to keep a consistent lock order.
 */
public interface WmsIdentityActionTokenStoragePort {

    /**
     * Locks the tenant row until the surrounding transaction completes.
     * Returns empty when the tenant does not exist.
     */
    Optional<TenantStatus> lockTenantForInvitation(long tenantId);

    /**
     * Revokes outstanding invitation tokens for one tenant/email pair.
     * Caller must hold the tenant invitation lock in the same transaction.
     */
    int revokeOutstandingInvitations(long tenantId, String normalizedEmail, Instant revokedAt);

    /**
     * Persists a digest-only action token and returns its generated database ID.
     */
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
     * Reads a token by digest without acquiring a row lock.
     * Use only to discover the tenant lock key; always re-read with
     * findByDigestForUpdate before performing a state transition.
     */
    Optional<WmsIdentityActionTokenRecord> findByDigest(
            byte[] tokenDigest,
            WmsIdentityActionTokenPurpose expectedPurpose);

    /**
     * Retrieves and locks a token row by its digest and expected purpose.
     */
    Optional<WmsIdentityActionTokenRecord> findByDigestForUpdate(
            byte[] tokenDigest,
            WmsIdentityActionTokenPurpose expectedPurpose);

    /**
     * Marks an unconsumed, unrevoked, unexpired token as consumed.
     * Returns one only when this transaction wins the consume race.
     */
    int markConsumed(long tokenId, Instant consumedAt);

    /**
     * Revokes an unconsumed and unrevoked token.
     */
    int markRevoked(long tokenId, Instant revokedAt);
}
