package com.zirocraft.zirologistics.wms.security.throttling.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Explicit deployment policy for login throttling.
 *
 * No production defaults are supplied: callers must provide reviewed,
 * positive windows, limits, and cooldown duration.
 */
public record WmsLoginThrottlePolicy(
        Duration sourceWindow,
        int sourceMaximumRequests,
        Duration accountFailureWindow,
        int accountFailureThreshold,
        Duration accountCooldown) {

    public WmsLoginThrottlePolicy {
        requireWholePositiveSeconds(sourceWindow, "sourceWindow");
        requireWholePositiveSeconds(accountFailureWindow, "accountFailureWindow");
        requireWholePositiveSeconds(accountCooldown, "accountCooldown");
        if (sourceMaximumRequests < 1) {
            throw new IllegalArgumentException("sourceMaximumRequests must be positive");
        }
        if (accountFailureThreshold < 1) {
            throw new IllegalArgumentException("accountFailureThreshold must be positive");
        }
    }

    private static void requireWholePositiveSeconds(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.getNano() != 0) {
            throw new IllegalArgumentException(name + " must be a positive whole-second duration");
        }
    }
}
