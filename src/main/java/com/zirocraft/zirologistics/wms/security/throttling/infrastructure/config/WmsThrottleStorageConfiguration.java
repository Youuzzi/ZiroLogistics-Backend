package com.zirocraft.zirologistics.wms.security.throttling.infrastructure.config;

import com.zirocraft.zirologistics.wms.security.throttling.application.WmsThrottleStoragePort;
import com.zirocraft.zirologistics.wms.security.throttling.infrastructure.persistence.MySqlWmsThrottleStorageAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Opt-in WMS throttle storage wiring.
 *
 * The adapter is absent unless both the WMS profile and the explicit MySQL
 * throttle-storage switch are active.
 */
@Configuration(proxyBeanMethods = false)
@Profile("wms")
@ConditionalOnProperty(
        prefix = "wms.security.throttle.storage",
        name = "mysql-enabled",
        havingValue = "true")
public class WmsThrottleStorageConfiguration {

    @Bean
    WmsThrottleStoragePort wmsThrottleStoragePort(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        return new MySqlWmsThrottleStorageAdapter(jdbcTemplate, transactionManager);
    }
}
