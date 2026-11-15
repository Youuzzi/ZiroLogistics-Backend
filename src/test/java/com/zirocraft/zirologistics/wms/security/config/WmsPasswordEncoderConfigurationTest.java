package com.zirocraft.zirologistics.wms.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WmsPasswordEncoderConfigurationTest {

    @Test
    void exposesArgon2idEncoderOnlyWhenWmsProfileIsActive() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("wms");
            context.register(WmsPasswordEncoderConfiguration.class);
            context.refresh();

            PasswordEncoder encoder = context.getBean("wmsPasswordEncoder", PasswordEncoder.class);
            String rawPassword = "test-only-password-42!";
            String encoded = encoder.encode(rawPassword);

            assertThat(encoded).startsWith("$argon2id$");
            assertThat(encoded).isNotEqualTo(rawPassword);
            assertThat(encoder.matches(rawPassword, encoded)).isTrue();
            assertThat(encoder.matches("incorrect-password", encoded)).isFalse();
        }
    }

    @Test
    void doesNotExposeWmsEncoderOutsideWmsProfile() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(WmsPasswordEncoderConfiguration.class);
            context.refresh();

            assertThatThrownBy(() -> context.getBean("wmsPasswordEncoder"))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }
}
