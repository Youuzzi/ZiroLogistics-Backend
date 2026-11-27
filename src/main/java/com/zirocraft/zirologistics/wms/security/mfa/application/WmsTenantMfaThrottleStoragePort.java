package com.zirocraft.zirologistics.wms.security.mfa.application;

import java.time.Instant;

/**
 * Durable atomic attempt counter for tenant MFA account, factor and source
 * namespaces. Persistence failures must be propagated so callers fail closed.
 */
public interface WmsTenantMfaThrottleStoragePort {

    /**
     * Atomically increments the selected fixed-window bucket and applies the
     * absolute block deadline when the post-increment count reaches threshold.
     * A caller must reject the operation whenever the returned deadline is
     * after decisionTime. Repeated calls cannot reset an existing block.
     */
    WmsTenantMfaThrottleDecision recordAttempt(
            WmsTenantMfaThrottleBucketKey key,
            Instant windowStartedAt,
            int threshold,
            Instant blockedUntil,
            Instant decisionTime);
}
