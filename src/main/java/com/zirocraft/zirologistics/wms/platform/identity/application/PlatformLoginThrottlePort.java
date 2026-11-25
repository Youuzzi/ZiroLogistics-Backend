package com.zirocraft.zirologistics.wms.platform.identity.application;

/**
 * Platform-specific login-throttling boundary.
 *
 * Implementations must use platform-domain-separated HMAC identifiers and
 * must fail closed when throttle storage is unavailable. Tenant throttle
 * buckets must never be reused as platform operator buckets.
 */
public interface PlatformLoginThrottlePort {
    Attempt beginAttempt(String normalizedEmail, String numericSourceAddress);
    void recordFailedCredential(Attempt attempt);
    void recordSuccessfulCredential(Attempt attempt);

    interface Attempt {
    }
}
