package com.zirocraft.zirologistics.wms.bootstrap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("wms")
public class WmsBootstrapConfiguration {
    @Bean
    WmsPlatformBootstrapService wmsPlatformBootstrapService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            @Qualifier("wmsPasswordEncoder") PasswordEncoder passwordEncoder) {
        return new WmsPlatformBootstrapService(jdbcTemplate, transactionManager, passwordEncoder);
    }
}
