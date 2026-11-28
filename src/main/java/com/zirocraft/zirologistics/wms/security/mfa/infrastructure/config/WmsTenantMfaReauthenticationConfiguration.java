package com.zirocraft.zirologistics.wms.security.mfa.infrastructure.config;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaReauthenticationGrantStoragePort;
import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaReauthenticationService;
import com.zirocraft.zirologistics.wms.security.mfa.infrastructure.persistence.MySqlWmsTenantMfaReauthenticationGrantStorageAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;

import java.security.SecureRandom;
import java.time.Clock;

/**
 * Opt-in composition for tenant password reauthentication.
 * Disabled by default; it does not expose HTTP routes or enable tenant MFA.
 */
@Configuration(proxyBeanMethods = false)
@Profile("wms")
@ConditionalOnProperty(
        prefix = "wms.security.tenant-mfa",
        name = "enabled",
        havingValue = "true")
@ConditionalOnProperty(
        prefix = "wms.security.tenant-mfa.reauthentication",
        name = "enabled",
        havingValue = "true")
public class WmsTenantMfaReauthenticationConfiguration {

    @Bean
    WmsTenantMfaReauthenticationGrantStoragePort wmsTenantMfaReauthenticationGrantStoragePort(
            JdbcTemplate jdbcTemplate) {
        return new MySqlWmsTenantMfaReauthenticationGrantStorageAdapter(jdbcTemplate);
    }

    @Bean
    WmsTenantMfaReauthenticationService wmsTenantMfaReauthenticationService(
            UserRepository userRepository,
            WmsMembershipAccessService membershipAccessService,
            @Qualifier("wmsPasswordEncoder") PasswordEncoder passwordEncoder,
            WmsTenantMfaReauthenticationGrantStoragePort grantStorage,
            PlatformTransactionManager transactionManager) {
        return new WmsTenantMfaReauthenticationService(
                userRepository, membershipAccessService, passwordEncoder, grantStorage,
                transactionManager, Clock.systemUTC(), new SecureRandom());
    }
}
