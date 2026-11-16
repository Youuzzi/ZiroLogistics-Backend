package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.identity.domain.WmsGeneratedIdentityActionToken;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Generates high-entropy, single-use WMS identity action bearer tokens.
 *
 * A raw token contains 256 bits from SecureRandom and is encoded as unpadded
 * URL-safe Base64. Persistence receives only SHA-256(raw random bytes).
 */
public final class WmsIdentityActionTokenGenerator {

    private static final int SECRET_LENGTH_BYTES = 32;
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private final SecureRandom secureRandom;

    public WmsIdentityActionTokenGenerator() {
        this(new SecureRandom());
    }

    WmsIdentityActionTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    public WmsGeneratedIdentityActionToken generate() {
        byte[] secret = new byte[SECRET_LENGTH_BYTES];
        secureRandom.nextBytes(secret);

        String rawToken = TOKEN_ENCODER.encodeToString(secret);
        byte[] digest = sha256(secret);
        return new WmsGeneratedIdentityActionToken(rawToken, digest);
    }

    /**
     * Decodes a presented canonical token and returns its persistence digest.
     * Padding and non-canonical Base64 representations are rejected.
     */
    public byte[] digestPresentedToken(String rawToken) {
        if (rawToken == null || rawToken.length() != 43 || rawToken.indexOf('=') >= 0) {
            throw new IllegalArgumentException("Identity action token is invalid");
        }

        final byte[] secret;
        try {
            secret = TOKEN_DECODER.decode(rawToken);
        } catch (IllegalArgumentException malformedToken) {
            throw new IllegalArgumentException("Identity action token is invalid");
        }

        if (secret.length != SECRET_LENGTH_BYTES || !TOKEN_ENCODER.encodeToString(secret).equals(rawToken)) {
            throw new IllegalArgumentException("Identity action token is invalid");
        }
        return sha256(secret);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(value);
        } catch (NoSuchAlgorithmException impossibleOnJavaRuntime) {
            throw new IllegalStateException("SHA-256 is unavailable", impossibleOnJavaRuntime);
        }
    }
}
