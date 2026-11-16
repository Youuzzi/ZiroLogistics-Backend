package com.zirocraft.zirologistics.wms.identity.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * External configuration for the AES-256-GCM identity-email outbox key ring.
 * Values must be supplied by deployment secret management, never committed.
 */
@ConfigurationProperties(prefix = "wms.identity.email.outbox.encryption")
public class WmsEmailOutboxEncryptionProperties {

    private int currentKeyVersion;
    private Map<Integer, String> keys = new LinkedHashMap<>();

    public int getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    public void setCurrentKeyVersion(int currentKeyVersion) {
        this.currentKeyVersion = currentKeyVersion;
    }

    public Map<Integer, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<Integer, String> keys) {
        this.keys = keys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(keys);
    }
}
