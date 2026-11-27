package com.zirocraft.zirologistics.wms.security.mfa.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistence boundary for the tenant TOTP factor enrollment lifecycle.
 * Implementations must keep pending factors unusable until a valid TOTP step
 * is atomically accepted during activation.
 */
public interface WmsTenantMfaFactorLifecyclePort {

    long createPendingFactor(
            String userPublicId,
            UUID factorPublicId,
            String encryptionKeyVersion,
            byte[] nonce,
            byte[] encryptedSeed,
            Instant createdAt,
            Instant expiresAt);

    boolean activatePendingFactor(
            String userPublicId,
            UUID factorPublicId,
            long matchedTimeStep,
            Instant activatedAt);

    boolean revokeFactor(
            String userPublicId,
            UUID factorPublicId,
            Instant revokedAt);
}
