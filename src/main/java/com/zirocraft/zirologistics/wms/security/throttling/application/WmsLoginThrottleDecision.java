package com.zirocraft.zirologistics.wms.security.throttling.application;

import java.time.Instant;

/**
 * Result of the committed pre-verification throttle decision.
 * Never include email, IP address, or HMAC digest in this value.
 */
public record WmsLoginThrottleDecision(
        Reason reason,
        long sourceAttemptCount,
        Instant accountBlockedUntil,
        WmsLoginThrottleService.Attempt attempt) {

    public WmsLoginThrottleDecision {
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        if (sourceAttemptCount < 1) {
            throw new IllegalArgumentException("sourceAttemptCount must be positive");
        }
        boolean allowed = reason == Reason.ALLOWED;
        if (allowed != (attempt != null)) {
            throw new IllegalArgumentException("Only allowed decisions may carry an attempt");
        }
    }

    public boolean allowed() {
        return reason == Reason.ALLOWED;
    }

    public enum Reason {
        ALLOWED,
        SOURCE_LIMIT_EXCEEDED,
        ACCOUNT_COOLDOWN_ACTIVE
    }
}
