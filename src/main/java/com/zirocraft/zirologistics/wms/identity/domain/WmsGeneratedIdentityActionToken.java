package com.zirocraft.zirologistics.wms.identity.domain;

import java.util.Objects;

/**
 * A newly issued identity action token.
 *
 * The raw bearer value is returned to the caller exactly once for delivery.
 * Only the defensive-copy digest accessor is intended for persistence.
 */
public final class WmsGeneratedIdentityActionToken {

    private final String rawToken;
    private final byte[] digest;

    WmsGeneratedIdentityActionToken(String rawToken, byte[] digest) {
        this.rawToken = Objects.requireNonNull(rawToken, "rawToken must not be null");
        Objects.requireNonNull(digest, "digest must not be null");
        if (digest.length != 32) {
            throw new IllegalArgumentException("Identity action token digest must contain 32 bytes");
        }
        this.digest = digest.clone();
    }

    /**
     * Returns the bearer secret for immediate delivery. Never log or persist it.
     */
    public String rawToken() {
        return rawToken;
    }

    /**
     * Returns the SHA-256 digest for persistence. The returned array is a copy.
     */
    public byte[] digest() {
        return digest.clone();
    }
}
