package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.identity.domain.WmsEncryptedEmailPayload;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AES-256-GCM encryption for identity-email outbox payloads.
 *
 * Keys are supplied by deployment configuration/secret management, never by
 * database rows. Associated data binds ciphertext to message ID, purpose,
 * normalized recipient, and key version.
 */
public final class WmsEmailOutboxEncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int MAX_PLAINTEXT_BYTES = 4_080;

    private final Map<Integer, SecretKey> keysByVersion;
    private final int currentKeyVersion;
    private final SecureRandom secureRandom;

    public WmsEmailOutboxEncryptionService(Map<Integer, byte[]> keyRing, int currentKeyVersion) {
        this(keyRing, currentKeyVersion, new SecureRandom());
    }

    WmsEmailOutboxEncryptionService(
            Map<Integer, byte[]> keyRing,
            int currentKeyVersion,
            SecureRandom secureRandom) {
        Objects.requireNonNull(keyRing, "keyRing must not be null");
        if (keyRing.isEmpty()) {
            throw new IllegalArgumentException("keyRing must not be empty");
        }
        if (currentKeyVersion < 1 || !keyRing.containsKey(currentKeyVersion)) {
            throw new IllegalArgumentException("current key version must exist in keyRing");
        }

        Map<Integer, SecretKey> copiedKeys = new HashMap<>();
        keyRing.forEach((version, keyBytes) -> {
            if (version == null || version < 1) {
                throw new IllegalArgumentException("all key versions must be positive");
            }
            Objects.requireNonNull(keyBytes, "key material must not be null");
            if (keyBytes.length != KEY_LENGTH_BYTES) {
                throw new IllegalArgumentException("AES-256 key material must contain 32 bytes");
            }
            copiedKeys.put(version, new SecretKeySpec(keyBytes.clone(), "AES"));
        });

        this.keysByVersion = Map.copyOf(copiedKeys);
        this.currentKeyVersion = currentKeyVersion;
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
    }

    public WmsEncryptedEmailPayload encrypt(
            UUID messageId,
            WmsIdentityActionTokenPurpose purpose,
            String recipientEmail,
            byte[] plaintext) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        String normalizedEmail = normalizeRecipient(recipientEmail);
        requirePlaintext(plaintext);

        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(nonce);

        byte[] plaintextCopy = plaintext.clone();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keysByVersion.get(currentKeyVersion),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(associatedData(messageId, purpose, normalizedEmail, currentKeyVersion));
            byte[] ciphertext = cipher.doFinal(plaintextCopy);
            return new WmsEncryptedEmailPayload(currentKeyVersion, nonce, ciphertext);
        } catch (GeneralSecurityException unavailableOrInvalidCrypto) {
            throw new IllegalStateException("Unable to encrypt WMS identity email payload", unavailableOrInvalidCrypto);
        } finally {
            java.util.Arrays.fill(plaintextCopy, (byte) 0);
            java.util.Arrays.fill(nonce, (byte) 0);
        }
    }

    public byte[] decrypt(
            UUID messageId,
            WmsIdentityActionTokenPurpose purpose,
            String recipientEmail,
            WmsEncryptedEmailPayload encryptedPayload) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(encryptedPayload, "encryptedPayload must not be null");
        String normalizedEmail = normalizeRecipient(recipientEmail);

        SecretKey key = keysByVersion.get(encryptedPayload.keyVersion());
        if (key == null) {
            throw new IllegalArgumentException("WMS identity email encryption key version is unavailable");
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_LENGTH_BITS, encryptedPayload.nonce()));
            cipher.updateAAD(associatedData(
                    messageId, purpose, normalizedEmail, encryptedPayload.keyVersion()));
            return cipher.doFinal(encryptedPayload.ciphertext());
        } catch (AEADBadTagException authenticationFailure) {
            throw new IllegalArgumentException("WMS identity email payload authentication failed");
        } catch (GeneralSecurityException unavailableOrInvalidCrypto) {
            throw new IllegalStateException("Unable to decrypt WMS identity email payload", unavailableOrInvalidCrypto);
        }
    }

    private static String normalizeRecipient(String recipientEmail) {
        return WmsEmailNormalizer.normalize(recipientEmail);
    }

    private static void requirePlaintext(byte[] plaintext) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        if (plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("plaintext size is outside the supported outbox payload range");
        }
    }

    private static byte[] associatedData(
            UUID messageId,
            WmsIdentityActionTokenPurpose purpose,
            String normalizedEmail,
            int keyVersion) {
        byte[] purposeBytes = purpose.name().getBytes(StandardCharsets.UTF_8);
        byte[] emailBytes = normalizedEmail.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(16 + Integer.BYTES + purposeBytes.length + 1 + emailBytes.length)
                .putLong(messageId.getMostSignificantBits())
                .putLong(messageId.getLeastSignificantBits())
                .putInt(keyVersion)
                .put(purposeBytes)
                .put((byte) 0)
                .put(emailBytes)
                .array();
    }
}
