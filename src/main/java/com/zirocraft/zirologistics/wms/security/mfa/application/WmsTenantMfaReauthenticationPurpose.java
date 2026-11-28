package com.zirocraft.zirologistics.wms.security.mfa.application;

/**
 * Explicit purposes accepted by the V32 one-time password reauthentication grant.
 * Keep this allowlist aligned with the database CHECK constraint.
 */
public enum WmsTenantMfaReauthenticationPurpose {
    MFA_FACTOR_ENROLLMENT,
    MFA_FACTOR_REVOCATION,
    MFA_FACTOR_RECOVERY
}
