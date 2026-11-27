package com.zirocraft.zirologistics.wms.platform.security;

import com.zirocraft.zirologistics.wms.security.config.WmsJwtProperties;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/** Startup guard for independently authenticated platform and tenant trust domains. */
final class PlatformTenantJwtTrustDomainGuard {
    PlatformTenantJwtTrustDomainGuard() {
    }

    static void validate(PlatformJwtProperties platform, WmsJwtProperties tenant) {
        Objects.requireNonNull(platform, "platform JWT properties must not be null");
        Objects.requireNonNull(tenant, "tenant JWT properties must not be null");

        byte[] platformPublicKey = publicKeyBytes(platform.getPublicKeyPem(), "platform");
        byte[] tenantPublicKey = publicKeyBytes(tenant.getPublicKeyPem(), "tenant");

        if (MessageDigest.isEqual(platformPublicKey, tenantPublicKey)) {
            throw new IllegalStateException("Platform and tenant JWT signing keys must be different");
        }
        if (sameNonBlank(platform.getIssuer(), tenant.getIssuer())) {
            throw new IllegalStateException("Platform and tenant JWT issuers must be different");
        }
        if (sameNonBlank(platform.getKeyId(), tenant.getKeyId())) {
            throw new IllegalStateException("Platform and tenant JWT key identifiers must be different");
        }
        if (sameNonBlank(platform.getAudience(), tenant.getTenantSelectionAudience())
                || sameNonBlank(platform.getAudience(), tenant.getAccessAudience())) {
            throw new IllegalStateException("Platform and tenant JWT audiences must be different");
        }
    }

    private static boolean sameNonBlank(String left, String right) {
        return left != null && !left.isBlank() && right != null && !right.isBlank() && left.equals(right);
    }

    private static byte[] publicKeyBytes(String pem, String domain) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException(domain + " JWT public key is required for trust-domain validation");
        }
        String begin = "-----BEGIN PUBLIC KEY-----";
        String end = "-----END PUBLIC KEY-----";
        String normalized = pem.strip();
        if (!normalized.startsWith(begin) || !normalized.endsWith(end)) {
            throw new IllegalStateException(domain + " JWT public key must use X.509 PUBLIC KEY PEM format");
        }
        String body = normalized.substring(begin.length(), normalized.length() - end.length())
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(domain + " JWT public key PEM is invalid", exception);
        }
    }
}
