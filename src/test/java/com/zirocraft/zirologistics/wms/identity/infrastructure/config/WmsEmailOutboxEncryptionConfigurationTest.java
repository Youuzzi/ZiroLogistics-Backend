package com.zirocraft.zirologistics.wms.identity.infrastructure.config;

import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxEncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WmsEmailOutboxEncryptionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("wms"))
            .withUserConfiguration(WmsEmailOutboxEncryptionConfiguration.class);

    @Test
    void encryptionBeanIsAbsentUnlessExplicitlyEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(WmsEmailOutboxEncryptionService.class);
        });
    }

    @Test
    void createsEncryptionBeanFromExternalVersionedKeyRing() {
        String encodedKey = Base64.getEncoder().encodeToString(new byte[32]);

        contextRunner
                .withPropertyValues(
                        "wms.identity.email.outbox.encryption.enabled=true",
                        "wms.identity.email.outbox.encryption.current-key-version=1",
                        "wms.identity.email.outbox.encryption.keys[1]=" + encodedKey)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WmsEmailOutboxEncryptionService.class);
                });
    }

    @Test
    void failsClosedWhenEnabledWithoutCurrentKey() {
        contextRunner
                .withPropertyValues(
                        "wms.identity.email.outbox.encryption.enabled=true",
                        "wms.identity.email.outbox.encryption.current-key-version=1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("current key version");
                });
    }

    @Test
    void failsClosedWhenKeyMaterialIsNotValidBase64() {
        contextRunner
                .withPropertyValues(
                        "wms.identity.email.outbox.encryption.enabled=true",
                        "wms.identity.email.outbox.encryption.current-key-version=1",
                        "wms.identity.email.outbox.encryption.keys[1]=not-base64")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("not valid Base64");
                });
    }

    @Test
    void failsClosedWhenAesKeyLengthIsNot256Bits() {
        String encodedKey = Base64.getEncoder().encodeToString(new byte[16]);

        contextRunner
                .withPropertyValues(
                        "wms.identity.email.outbox.encryption.enabled=true",
                        "wms.identity.email.outbox.encryption.current-key-version=1",
                        "wms.identity.email.outbox.encryption.keys[1]=" + encodedKey)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("32 bytes");
                });
    }
}
