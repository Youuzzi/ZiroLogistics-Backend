package com.zirocraft.zirologistics.wms.security.mfa.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistence boundary for short-lived, single-use tenant MFA password
 * reauthentication grants. Implementations store only a cryptographic digest.
 */
public interface WmsTenantMfaReauthenticationGrantStoragePort {

    void insert(
            UUID publicId,
            byte[] grantDigest,
            long userId,
            long tenantId,
            long membershipId,
            long credentialVersion,
            WmsTenantMfaReauthenticationPurpose purpose,
            Instant createdAt,
            Instant expiresAt);

    /**
     * Atomically consumes a grant only when every identity and purpose binding
     * matches and the grant is still unexpired and unused.
     */
    boolean consume(
            byte[] grantDigest,
            long userId,
            long tenantId,
            long membershipId,
            long credentialVersion,
            WmsTenantMfaReauthenticationPurpose purpose,
            Instant consumedAt);

    /** Revokes all outstanding grants after a credential or account security change. */
    int revokeOutstandingForUser(long userId, Instant revokedAt);
}
