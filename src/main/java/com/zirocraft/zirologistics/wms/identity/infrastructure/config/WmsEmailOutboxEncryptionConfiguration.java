package com.zirocraft.zirologistics.wms.identity.infrastructure.config;

import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxEncryptionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Opt-in WMS email-outbox encryption wiring.
 *
 * The feature is absent unless explicitly enabled. Invalid or incomplete key
 * configuration fails application startup rather than falling back to a
 * default or plaintext mode.
 */
@Configuration(proxyBeanMethods = false)
@Profile("wms")
@ConditionalOnProperty(
        prefix = "wms.identity.email.outbox.encryption",
        name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(WmsEmailOutboxEncryptionProperties.class)
public class WmsEmailOutboxEncryptionConfiguration {

    @Bean
    WmsEmailOutboxEncryptionService wmsEmailOutboxEncryptionService(
            WmsEmailOutboxEncryptionProperties properties) {
        Map<Integer, byte[]> keyRing = new HashMap<>();
        properties.getKeys().forEach((version, encodedKey) -> {
            if (encodedKey == null || encodedKey.isBlank()) {
                throw new IllegalStateException("WMS email outbox encryption key material is missing");
            }
            try {
                keyRing.put(version, Base64.getDecoder().decode(encodedKey));
            } catch (IllegalArgumentException malformedKey) {
                throw new IllegalStateException("WMS email outbox encryption key is not valid Base64");
            }
        });

        return new WmsEmailOutboxEncryptionService(keyRing, properties.getCurrentKeyVersion());
    }
}
