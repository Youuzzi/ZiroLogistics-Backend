package com.zirocraft.zirologistics.wms.identity.domain;

import java.util.Objects;

/**
 * Encrypted identity-email payload ready for persistence in the outbox.
 * Byte arrays are defensively copied at construction and access.
 */
public final class WmsEncryptedEmailPayload {

    private final int keyVersion;
    private final byte[] nonce;
    private final byte[] ciphertext;

    public WmsEncryptedEmailPayload(int keyVersion, byte[] nonce, byte[] ciphertext) {
        if (keyVersion < 1) {
            throw new IllegalArgumentException("keyVersion must be positive");
        }
        Objects.requireNonNull(nonce, "nonce must not be null");
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
        if (nonce.length != 12) {
            throw new IllegalArgumentException("AES-GCM nonce must contain 12 bytes");
        }
        if (ciphertext.length <= 16) {
            throw new IllegalArgumentException("ciphertext must include a non-empty payload and GCM tag");
        }
        this.keyVersion = keyVersion;
        this.nonce = nonce.clone();
        this.ciphertext = ciphertext.clone();
    }

    public int keyVersion() {
        return keyVersion;
    }

    public byte[] nonce() {
        return nonce.clone();
    }

    public byte[] ciphertext() {
        return ciphertext.clone();
    }
}
