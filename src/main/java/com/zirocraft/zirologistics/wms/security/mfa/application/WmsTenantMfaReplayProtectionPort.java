package com.zirocraft.zirologistics.wms.security.mfa.application;

/**
 * Persistence boundary for accepting a verified TOTP time step.
 *
 * Implementations must atomically advance the factor watermark. A successful
 * result means this exact step was accepted once; false means the factor is
 * not eligible or the step was already accepted.
 */
public interface WmsTenantMfaReplayProtectionPort {
    boolean acceptTimeStep(long factorId, long userId, long matchedTimeStep);
}
