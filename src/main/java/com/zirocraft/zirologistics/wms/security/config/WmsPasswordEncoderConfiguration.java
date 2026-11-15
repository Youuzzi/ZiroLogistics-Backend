package com.zirocraft.zirologistics.wms.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * WMS-only password hashing policy.
 *
 * The legacy runtime intentionally does not import this configuration.
 */
@Configuration(proxyBeanMethods = false)
@Profile("wms")
public class WmsPasswordEncoderConfiguration {

    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;

    @Bean("wmsPasswordEncoder")
    public PasswordEncoder wmsPasswordEncoder() {
        return new Argon2PasswordEncoder(
                SALT_LENGTH_BYTES,
                HASH_LENGTH_BYTES,
                PARALLELISM,
                MEMORY_KIB,
                ITERATIONS
        );
    }
}
