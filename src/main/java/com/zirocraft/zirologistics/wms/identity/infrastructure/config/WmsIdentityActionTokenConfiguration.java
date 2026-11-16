package com.zirocraft.zirologistics.wms.identity.infrastructure.config;

import com.zirocraft.zirologistics.wms.identity.application.WmsIdentityActionTokenGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * WMS-only identity action token generator wiring.
 */
@Configuration(proxyBeanMethods = false)
@Profile("wms")
public class WmsIdentityActionTokenConfiguration {

    @Bean
    WmsIdentityActionTokenGenerator wmsIdentityActionTokenGenerator() {
        return new WmsIdentityActionTokenGenerator();
    }
}
