package com.zirocraft.zirologistics.wms.security.authentication.infrastructure.config;

import com.zirocraft.zirologistics.wms.identity.application.WmsUserLookupService;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.WmsLoginMembershipRepository;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtTokenService;
import com.zirocraft.zirologistics.wms.security.authentication.application.WmsCredentialAuthenticationService;
import com.zirocraft.zirologistics.wms.security.authentication.application.WmsUnknownUserPasswordVerifier;
import com.zirocraft.zirologistics.wms.security.config.WmsPasswordEncoderConfiguration;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsClientAddressResolver;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsLoginThrottlePolicy;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsLoginThrottleService;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsThrottleKeyHasher;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsThrottleStoragePort;
import com.zirocraft.zirologistics.wms.security.throttling.infrastructure.config.WmsLoginThrottleProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;

/**
 * Composes password authentication only when JWT is enabled and the explicitly
 * configured shared MySQL throttle adapter is available. Missing configuration
 * therefore fails closed rather than silently disabling rate limiting.
 */
@Configuration(proxyBeanMethods = false)
@Profile("wms")
@ConditionalOnProperty(prefix = "wms.security.jwt", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WmsLoginThrottleProperties.class)
public class WmsCredentialAuthenticationConfiguration {

    @Bean
    WmsLoginThrottleService wmsLoginThrottleService(
            WmsLoginThrottleProperties properties,
            WmsThrottleStoragePort storage) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.getHmacKeyBase64());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("WMS throttle HMAC key must be valid Base64");
        }
        try {
            WmsThrottleKeyHasher hasher = new WmsThrottleKeyHasher(properties.getHmacKeyVersion(), key);
            WmsClientAddressResolver addressResolver =
                    new WmsClientAddressResolver(properties.getTrustedProxyCidrs());
            WmsLoginThrottlePolicy policy = new WmsLoginThrottlePolicy(
                    properties.getSourceWindow(),
                    properties.getSourceMaximumRequests(),
                    properties.getAccountFailureWindow(),
                    properties.getAccountFailureThreshold(),
                    properties.getAccountCooldown());
            return new WmsLoginThrottleService(hasher, addressResolver, storage, policy, Clock.systemUTC());
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Bean
    WmsCredentialAuthenticationService wmsCredentialAuthenticationService(
            WmsLoginThrottleService throttleService,
            WmsUserLookupService userLookupService,
            @Qualifier("wmsPasswordEncoder") PasswordEncoder passwordEncoder,
            WmsUnknownUserPasswordVerifier unknownUserPasswordVerifier,
            WmsLoginMembershipRepository membershipRepository,
            WmsJwtTokenService tokenService) {
        return new WmsCredentialAuthenticationService(
                throttleService, userLookupService, passwordEncoder,
                unknownUserPasswordVerifier, membershipRepository, tokenService);
    }
}
