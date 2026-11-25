package com.zirocraft.zirologistics.wms.identity.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WmsEmailOutboxDeliveryConfigurationTest {

    @Test
    void acceptsLeaseLongerThanEverySmtpTimeout() {
        WmsEmailOutboxDeliveryProperties properties = validProperties();
        properties.setProcessingLease(Duration.ofSeconds(30));

        assertDoesNotThrow(() -> WmsEmailOutboxDeliveryConfiguration.validateConfiguration(properties));
    }

    @Test
    void rejectsLeaseThatCanExpireBeforeConnectionTimeout() {
        WmsEmailOutboxDeliveryProperties properties = validProperties();
        properties.setProcessingLease(Duration.ofSeconds(4));
        properties.setSmtpConnectionTimeoutMs(5_000);

        assertThrows(IllegalStateException.class,
                () -> WmsEmailOutboxDeliveryConfiguration.validateConfiguration(properties));
    }

    @Test
    void rejectsLeaseThatCanExpireBeforeWriteTimeout() {
        WmsEmailOutboxDeliveryProperties properties = validProperties();
        properties.setProcessingLease(Duration.ofSeconds(9));
        properties.setSmtpWriteTimeoutMs(10_000);

        assertThrows(IllegalStateException.class,
                () -> WmsEmailOutboxDeliveryConfiguration.validateConfiguration(properties));
    }

    @Test
    void rejectsZeroOrNegativeProcessingLease() {
        WmsEmailOutboxDeliveryProperties properties = validProperties();
        properties.setProcessingLease(Duration.ZERO);

        assertThrows(IllegalStateException.class,
                () -> WmsEmailOutboxDeliveryConfiguration.validateConfiguration(properties));
    }

    private static WmsEmailOutboxDeliveryProperties validProperties() {
        WmsEmailOutboxDeliveryProperties properties = new WmsEmailOutboxDeliveryProperties();
        properties.setSmtpHost("smtp.example.test");
        properties.setSmtpUsername("user");
        properties.setSmtpPassword("secret");
        properties.setFromAddress("noreply@example.test");
        properties.setActionBaseUrl("https://wms.example.test");
        return properties;
    }
}
