package com.zirocraft.zirologistics.wms.security.mfa.application;

import java.time.Instant;
import java.util.Objects;

/**
 * One-time raw reauthentication grant returned only to the authenticated caller.
 * The raw value must never be logged or persisted.
 */
public record WmsTenantMfaReauthenticationGrant(String value, Instant expiresAt) {
    public WmsTenantMfaReauthenticationGrant {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Grant value must not be blank");
        }
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
