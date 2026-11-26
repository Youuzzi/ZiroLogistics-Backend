package com.zirocraft.zirologistics.wms.security.mfa.application;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Cryptographic primitives for tenant TOTP factors.
 *
 * Persistence callers MUST atomically compare-and-set lastAcceptedTimeStep
 * after verification. This class deliberately does not claim to prevent
 * replay by itself; that guarantee belongs to the transactional repository.
 */
public final class WmsTenantTotpService {
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int SECRET_BYTES = 20;
    private static final int DIGITS = 6;
    private static final long PERIOD_SECONDS = 30L;
    private static final long MAX_TIME_STEP = Long.MAX_VALUE >>> 1;
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private final byte[] encryptionKey;
    private final String keyVersion;
    private final SecureRandom random;

    public WmsTenantTotpService(byte[] encryptionKey, String keyVersion) {
        this(encryptionKey, keyVersion, new SecureRandom());
    }

    WmsTenantTotpService(byte[] encryptionKey, String keyVersion, SecureRandom random) {
        Objects.requireNonNull(encryptionKey, "encryptionKey must not be null");
        if (encryptionKey.length != 32) {
            throw new IllegalArgumentException("Tenant MFA AES-256 key must contain exactly 32 bytes");
        }
        if (keyVersion == null || !keyVersion.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("keyVersion must be a non-empty safe identifier");
        }
        this.encryptionKey = encryptionKey.clone();
        this.keyVersion = keyVersion;
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    public byte[] generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return secret;
    }

    public String encodeBase32(byte[] secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        if (secret.length < 16 || secret.length > 64) {
            throw new IllegalArgumentException("TOTP secret must contain between 16 and 64 bytes");
        }
        StringBuilder encoded = new StringBuilder((secret.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : secret) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                encoded.append(BASE32[(buffer >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) encoded.append(BASE32[(buffer << (5 - bits)) & 31]);
        return encoded.toString();
    }

    public EncryptedSecret encryptSecret(UUID userId, byte[] secret) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(secret, "secret must not be null");
        if (secret.length < 16 || secret.length > 64) {
            throw new IllegalArgumentException("TOTP secret must contain between 16 and 64 bytes");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(userId, keyVersion));
            return new EncryptedSecret(keyVersion, nonce, cipher.doFinal(secret));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Unable to encrypt tenant MFA secret", failure);
        } finally {
            Arrays.fill(nonce, (byte) 0);
        }
    }

    public byte[] decryptSecret(UUID userId, EncryptedSecret encrypted) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(encrypted, "encrypted must not be null");
        if (!keyVersion.equals(encrypted.keyVersion())) {
            throw new IllegalArgumentException("Tenant MFA encryption key version is unavailable");
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
            cipher.updateAAD(associatedData(userId, encrypted.keyVersion()));
            return cipher.doFinal(encrypted.ciphertext());
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Tenant MFA secret authentication failed", failure);
        }
    }

    /** RFC 6238 TOTP using HMAC-SHA1, six digits and a 30-second time step. */
    public String codeAt(byte[] secret, Instant instant) {
        Objects.requireNonNull(secret, "secret must not be null");
        Objects.requireNonNull(instant, "instant must not be null");
        if (secret.length < 16 || secret.length > 64) {
            throw new IllegalArgumentException("TOTP secret must contain between 16 and 64 bytes");
        }
        long epochSecond = instant.getEpochSecond();
        if (epochSecond < 0) throw new IllegalArgumentException("TOTP time must not be before Unix epoch");
        long timeStep = epochSecond / PERIOD_SECONDS;
        if (timeStep > MAX_TIME_STEP) throw new IllegalArgumentException("TOTP time is outside supported range");
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format(java.util.Locale.ROOT, "%06d", otp);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Unable to calculate tenant TOTP", failure);
        }
    }

    /**
     * Checks the current 30-second step with one step of clock skew on either
     * side. The returned step MUST be persisted with an atomic
     * lastAcceptedTimeStep < matchedStep condition to prevent replay.
     */
    public OptionalLong verify(byte[] secret, String submittedCode, Instant now) {
        Objects.requireNonNull(submittedCode, "submittedCode must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!submittedCode.matches("[0-9]{6}")) return OptionalLong.empty();
        long current = now.getEpochSecond() / PERIOD_SECONDS;
        for (long step = current + 1; step >= Math.max(0, current - 1); step--) {
            String expected = codeAt(secret, Instant.ofEpochSecond(step * PERIOD_SECONDS));
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    submittedCode.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    public String provisioningUri(byte[] secret, String accountName, String issuer) {
        Objects.requireNonNull(accountName, "accountName must not be null");
        Objects.requireNonNull(issuer, "issuer must not be null");
        if (accountName.isBlank() || issuer.isBlank() || accountName.length() > 254 || issuer.length() > 64) {
            throw new IllegalArgumentException("TOTP account and issuer labels are invalid");
        }
        String label = urlEncode(issuer) + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label + "?secret=" + encodeBase32(secret)
                + "&issuer=" + urlEncode(issuer) + "&algorithm=SHA1&digits=6&period=30";
    }

    private static byte[] associatedData(UUID userId, String version) {
        byte[] versionBytes = version.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(16 + Integer.BYTES + versionBytes.length)
                .putLong(userId.getMostSignificantBits())
                .putLong(userId.getLeastSignificantBits())
                .putInt(versionBytes.length)
                .put(versionBytes)
                .array();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record EncryptedSecret(String keyVersion, byte[] nonce, byte[] ciphertext) {
        public EncryptedSecret {
            Objects.requireNonNull(keyVersion, "keyVersion must not be null");
            nonce = Objects.requireNonNull(nonce, "nonce must not be null").clone();
            ciphertext = Objects.requireNonNull(ciphertext, "ciphertext must not be null").clone();
            if (nonce.length != NONCE_BYTES || ciphertext.length < 16) {
                throw new IllegalArgumentException("Encrypted TOTP secret has invalid dimensions");
            }
        }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
    }
}
