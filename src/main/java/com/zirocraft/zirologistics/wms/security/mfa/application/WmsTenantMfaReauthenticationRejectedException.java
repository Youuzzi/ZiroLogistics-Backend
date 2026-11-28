package com.zirocraft.zirologistics.wms.security.mfa.application;

/**
 * Generic rejection for invalid credentials, stale tenant identity, or unusable grants.
 * Callers must not expose which validation failed.
 */
public final class WmsTenantMfaReauthenticationRejectedException extends RuntimeException {
    public WmsTenantMfaReauthenticationRejectedException() {
        super("Tenant MFA reauthentication could not be completed");
    }
}
