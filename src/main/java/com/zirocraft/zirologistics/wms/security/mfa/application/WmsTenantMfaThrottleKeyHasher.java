package com.zirocraft.zirologistics.wms.security.mfa.application;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Creates domain-separated, non-reversible identifiers for tenant MFA abuse
 * buckets. Its key must be distinct from login throttling, JWT and MFA seed
 * encryption material.
 */
public final class WmsTenantMfaThrottleKeyHasher {
    private static final String ALGORITHM = "HmacSHA256";
    private static final String DOMAIN = "zirologistics:wms:tenant-mfa-throttle:v1";

    private final int keyVersion;
    private final SecretKeySpec key;

    public WmsTenantMfaThrottleKeyHasher(int keyVersion, byte[] secret) {
        if (keyVersion < 1 || keyVersion > 65_535) {
            throw new IllegalArgumentException("keyVersion must be between 1 and 65535");
        }
        Objects.requireNonNull(secret, "secret must not be null");
        if (secret.length < 32) {
            throw new IllegalArgumentException("Tenant MFA throttle key must contain at least 32 bytes");
        }
        this.keyVersion = keyVersion;
        this.key = new SecretKeySpec(secret.clone(), ALGORITHM);
    }

    public WmsTenantMfaThrottleBucketKey hashAccount(String userPublicId) {
        return hash(WmsTenantMfaThrottleBucketType.ACCOUNT, userPublicId);
    }

    public WmsTenantMfaThrottleBucketKey hashFactor(String factorPublicId) {
        return hash(WmsTenantMfaThrottleBucketType.FACTOR, factorPublicId);
    }

    /** The caller must pass a canonical address resolved under the trusted-proxy policy. */
    public WmsTenantMfaThrottleBucketKey hashSource(String canonicalAddress) {
        return hash(WmsTenantMfaThrottleBucketType.SOURCE, canonicalAddress);
    }

    private WmsTenantMfaThrottleBucketKey hash(
            WmsTenantMfaThrottleBucketType type, String identifier) {
        if (identifier == null || identifier.isBlank() || !identifier.equals(identifier.strip())) {
            throw new IllegalArgumentException("Throttle identifier must be canonical and non-blank");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal((DOMAIN + ":key-version:" + keyVersion + ":" + type.name() + ":" + identifier)
                    .getBytes(StandardCharsets.UTF_8));
            return new WmsTenantMfaThrottleBucketKey(
                    type, keyVersion, HexFormat.of().formatHex(digest));
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to calculate tenant MFA throttle identifier", failure);
        }
    }
}
