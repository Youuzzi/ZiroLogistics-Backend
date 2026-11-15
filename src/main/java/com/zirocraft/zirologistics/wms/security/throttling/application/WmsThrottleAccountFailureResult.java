package com.zirocraft.zirologistics.wms.security.throttling.application;

import java.time.Instant;

/**
 * Committed account failure state after one failed credential outcome.
 *
 * A null blockedUntil means the policy threshold has not been reached.
 */
public record WmsThrottleAccountFailureResult(
        long failureCount,
        Instant blockedUntil) {

    public WmsThrottleAccountFailureResult {
        if (failureCount < 1) {
            throw new IllegalArgumentException("failureCount must be positive");
        }
    }
}
