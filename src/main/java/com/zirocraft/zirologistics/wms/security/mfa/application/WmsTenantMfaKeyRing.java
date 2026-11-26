package com.zirocraft.zirologistics.wms.security.mfa.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable tenant-MFA encryption key ring.
 *
 * The active key encrypts new factor seeds. Retained versions remain available
 * for decrypting existing seeds until those records have been re-encrypted.
 */
public final class WmsTenantMfaKeyRing {
    private static final int KEY_BYTES = 32;
    private static final int MAX_KEYS = 16;

    private final String activeKeyVersion;
    private final Map<String, byte[]> keys;

    public WmsTenantMfaKeyRing(String activeKeyVersion, Map<String, byte[]> keys) {
        if (activeKeyVersion == null || !activeKeyVersion.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Active tenant MFA key version is invalid");
        }
        Objects.requireNonNull(keys, "keys must not be null");
        if (keys.isEmpty() || keys.size() > MAX_KEYS) {
            throw new IllegalArgumentException("Tenant MFA key ring must contain between 1 and 16 keys");
        }

        Map<String, byte[]> copy = new LinkedHashMap<>();
        keys.forEach((version, material) -> {
            if (version == null || !version.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("Tenant MFA key version is invalid");
            }
            Objects.requireNonNull(material, "Tenant MFA key material must not be null");
            if (material.length != KEY_BYTES) {
                throw new IllegalArgumentException("Every tenant MFA AES-256 key must contain exactly 32 bytes");
            }
            copy.put(version, material.clone());
        });
        if (!copy.containsKey(activeKeyVersion)) {
            throw new IllegalArgumentException("Active tenant MFA key version is not present in the key ring");
        }
        this.activeKeyVersion = activeKeyVersion;
        this.keys = Map.copyOf(copy);
    }

    public String activeKeyVersion() {
        return activeKeyVersion;
    }

    /**
     * Returns a defensive copy. Unknown versions fail closed; callers must not
     * silently fall back to the active key for ciphertext encrypted previously.
     */
    public byte[] keyFor(String version) {
        byte[] key = keys.get(version);
        if (key == null) {
            throw new IllegalArgumentException("Tenant MFA encryption key version is unavailable");
        }
        return key.clone();
    }
}
