package com.zirocraft.zirologistics.wms.security.mfa.application;

/** Independent namespaces for tenant MFA abuse controls. */
public enum WmsTenantMfaThrottleBucketType {
    ACCOUNT,
    FACTOR,
    SOURCE
}
