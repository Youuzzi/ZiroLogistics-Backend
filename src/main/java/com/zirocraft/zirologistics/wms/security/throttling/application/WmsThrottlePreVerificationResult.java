package com.zirocraft.zirologistics.wms.security.throttling.application;

import java.time.Instant;

/**
 * Committed state returned by the atomic pre-password-verification operation.
 *
 * A null accountBlockedUntil means no active account cooldown was observed
 * at the operation's supplied decision time.
 */
public record WmsThrottlePreVerificationResult(
        long sourceAttemptCount,
        Instant accountBlockedUntil) {

    public WmsThrottlePreVerificationResult {
        if (sourceAttemptCount < 1) {
            throw new IllegalArgumentException("sourceAttemptCount must be positive");
        }
    }

    public boolean accountCooldownActiveAt(Instant decisionTime) {
        if (decisionTime == null) {
            throw new IllegalArgumentException("decisionTime must not be null");
        }
        return accountBlockedUntil != null && accountBlockedUntil.isAfter(decisionTime);
    }
}
