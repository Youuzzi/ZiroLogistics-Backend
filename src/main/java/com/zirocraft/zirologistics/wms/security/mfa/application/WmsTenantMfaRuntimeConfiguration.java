package com.zirocraft.zirologistics.wms.security.mfa.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime boundary for tenant MFA encryption material.
 *
 * Keys are supplied by deployment secret management as standard Base64 values.
 * This configuration is deliberately disabled unless explicitly enabled.
 */
@Configuration(proxyBeanMethods = false)
public class WmsTenantMfaRuntimeConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "wms.security.tenant-mfa", name = "enabled", havingValue = "true")
    WmsTenantMfaKeyRing wmsTenantMfaKeyRing(
            @Value("${wms.security.tenant-mfa.active-key-version:}") String activeVersion,
            @Value("${wms.security.tenant-mfa.keys:}") String encodedKeys) {
        return createKeyRing(activeVersion, encodedKeys);
    }

    @Bean
    @ConditionalOnProperty(prefix = "wms.security.tenant-mfa", name = "enabled", havingValue = "true")
    WmsTenantTotpService wmsTenantTotpService(WmsTenantMfaKeyRing keyRing) {
        return new WmsTenantTotpService(keyRing);
    }

    static WmsTenantMfaKeyRing createKeyRing(String activeVersion, String encodedKeys) {
        if (activeVersion == null || activeVersion.isBlank()) {
            throw new IllegalStateException("Tenant MFA active key version must be configured");
        }
        if (encodedKeys == null || encodedKeys.isBlank()) {
            throw new IllegalStateException("Tenant MFA encryption keys must be configured");
        }

        Map<String, byte[]> decoded = new LinkedHashMap<>();
        try {
            for (String entry : encodedKeys.split(",", -1)) {
                int separator = entry.indexOf('=');
                if (separator <= 0 || separator == entry.length() - 1
                        || entry.indexOf('=', separator + 1) >= 0) {
                    throw new IllegalStateException("Tenant MFA key entry has invalid format");
                }
                String version = entry.substring(0, separator);
                String material = entry.substring(separator + 1);
                if (decoded.containsKey(version)) {
                    throw new IllegalStateException("Tenant MFA key versions must be unique");
                }
                try {
                    decoded.put(version, Base64.getDecoder().decode(material));
                } catch (IllegalArgumentException invalidBase64) {
                    throw new IllegalStateException("Tenant MFA key material must be valid Base64", invalidBase64);
                }
            }
            return new WmsTenantMfaKeyRing(activeVersion, decoded);
        } finally {
            decoded.values().forEach(key -> Arrays.fill(key, (byte) 0));
        }
    }
}
