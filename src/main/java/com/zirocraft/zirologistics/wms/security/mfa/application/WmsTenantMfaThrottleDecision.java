package com.zirocraft.zirologistics.wms.security.mfa.application;

import java.time.Instant;

public record WmsTenantMfaThrottleDecision(long attemptCount, Instant blockedUntil) {
    public WmsTenantMfaThrottleDecision {
        if (attemptCount < 1 || attemptCount > 1_000_000) {
            throw new IllegalArgumentException("attemptCount must be between 1 and 1000000");
        }
    }

    public boolean blockedAt(Instant instant) {
        return blockedUntil != null && blockedUntil.isAfter(instant);
    }
}
