package com.zirocraft.zirologistics.wms.security.throttling.application;

import com.zirocraft.zirologistics.wms.identity.application.WmsEmailNormalizer;
import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketKey;
import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Produces non-reversible, domain-separated identifiers for login throttle buckets.
 *
 * The HMAC key must be a dedicated secret of at least 256 bits supplied by
 * deployment secret management. It must not be reused as a JWT or password key.
 */
public final class WmsThrottleKeyHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DOMAIN_PREFIX = "zirologistics:wms:login-throttle:v1";

    private final int keyVersion;
    private final SecretKeySpec secretKey;

    public WmsThrottleKeyHasher(int keyVersion, byte[] secret) {
        if (keyVersion < 1 || keyVersion > 65_535) {
            throw new IllegalArgumentException("keyVersion must be between 1 and 65535");
        }
        Objects.requireNonNull(secret, "secret must not be null");
        if (secret.length < 32) {
            throw new IllegalArgumentException("Throttle HMAC secret must contain at least 32 bytes");
        }
        this.keyVersion = keyVersion;
        this.secretKey = new SecretKeySpec(secret.clone(), HMAC_ALGORITHM);
    }

    public WmsThrottleBucketKey hashAccountEmail(String submittedEmail) {
        String normalizedEmail = WmsEmailNormalizer.normalize(submittedEmail);
        return hash(WmsThrottleBucketType.ACCOUNT, normalizedEmail);
    }

    /**
     * Hashes a numeric client IP address. Forwarded headers must first be
     * interpreted by a separately configured trusted-proxy policy.
     */
    public WmsThrottleBucketKey hashSourceAddress(String numericAddress) {
        return hash(WmsThrottleBucketType.SOURCE, canonicalizeAddress(numericAddress));
    }

    private WmsThrottleBucketKey hash(WmsThrottleBucketType type, String canonicalValue) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secretKey);
            String message = DOMAIN_PREFIX + ":" + type.name() + ":" + canonicalValue;
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return new WmsThrottleBucketKey(type, keyVersion, HexFormat.of().formatHex(digest));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate WMS throttle identifier", exception);
        }
    }

    private static String canonicalizeAddress(String address) {
        if (address == null || address.isBlank() || !address.equals(address.strip())) {
            throw new IllegalArgumentException("Client address must be a canonical numeric IP address");
        }
        if (address.indexOf('%') >= 0 || address.startsWith("[") || address.endsWith("]")) {
            throw new IllegalArgumentException("Scoped or bracketed IP addresses are not supported");
        }

        try {
            InetAddress parsed;
            if (address.indexOf(':') >= 0) {
                if (!address.matches("[0-9a-fA-F:.]+")) {
                    throw new IllegalArgumentException("Client address must be a numeric IPv6 address");
                }
                parsed = InetAddress.getByName(address);
                if (!(parsed instanceof Inet6Address)) {
                    throw new IllegalArgumentException("Client address must be a numeric IPv6 address");
                }
            } else {
                String[] octets = address.split("\\.", -1);
                if (octets.length != 4) {
                    throw new IllegalArgumentException("Client address must be a numeric IPv4 or IPv6 address");
                }
                byte[] bytes = new byte[4];
                for (int i = 0; i < octets.length; i++) {
                    if (!octets[i].matches("0|[1-9][0-9]{0,2}")) {
                        throw new IllegalArgumentException("Client address must be a canonical numeric IPv4 address");
                    }
                    int value = Integer.parseInt(octets[i]);
                    if (value > 255) {
                        throw new IllegalArgumentException("IPv4 octet must not exceed 255");
                    }
                    bytes[i] = (byte) value;
                }
                parsed = InetAddress.getByAddress(bytes);
            }
            return parsed.getHostAddress().toLowerCase(java.util.Locale.ROOT);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Client address must be a valid numeric IP address", exception);
        }
    }
}
