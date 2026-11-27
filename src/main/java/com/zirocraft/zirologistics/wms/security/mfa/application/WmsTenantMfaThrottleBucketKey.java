package com.zirocraft.zirologistics.wms.security.mfa.application;

import java.util.Objects;

/** HMAC-derived bucket identity; raw account, factor and source values never enter persistence. */
public record WmsTenantMfaThrottleBucketKey(
        WmsTenantMfaThrottleBucketType type,
        int keyVersion,
        String digestHex) {

    public WmsTenantMfaThrottleBucketKey {
        Objects.requireNonNull(type, "type must not be null");
        if (keyVersion < 1 || keyVersion > 65_535) {
            throw new IllegalArgumentException("keyVersion must be between 1 and 65535");
        }
        if (digestHex == null || !digestHex.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("digestHex must contain exactly 32 bytes in hexadecimal");
        }
        digestHex = digestHex.toLowerCase(java.util.Locale.ROOT);
    }
}
