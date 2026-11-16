package com.zirocraft.zirologistics.wms.identity.infrastructure.config;

import com.zirocraft.zirologistics.wms.identity.application.WmsPlatformBootstrapCommand;
import com.zirocraft.zirologistics.wms.identity.application.WmsPlatformBootstrapResult;
import com.zirocraft.zirologistics.wms.identity.application.WmsPlatformBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Explicitly opt-in, non-HTTP bootstrap entry point.
 *
 * It is loaded only when both the WMS runtime and the wms-bootstrap profile are
 * active and wms.bootstrap.enabled=true.
 */
@Configuration(proxyBeanMethods = false)
@Profile("wms-bootstrap")
@ConditionalOnProperty(prefix = "wms.bootstrap", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WmsPlatformBootstrapProperties.class)
public class WmsPlatformBootstrapConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WmsPlatformBootstrapConfiguration.class);

    @Bean
    ApplicationRunner wmsPlatformBootstrapRunner(
            WmsPlatformBootstrapService bootstrapService,
            WmsPlatformBootstrapProperties properties) {
        return args -> {
            WmsPlatformBootstrapResult result = bootstrapService.bootstrap(
                    new WmsPlatformBootstrapCommand(
                            properties.getTenantCode(),
                            properties.getTenantLegalName(),
                            properties.getTenantDisplayName(),
                            properties.getAdminEmail(),
                            properties.getAdminFullName(),
                            properties.getAdminPassword()));

            log.info("WMS platform bootstrap completed: tenantPublicId={}, adminUserPublicId={}",
                    result.tenantPublicId(), result.adminUserPublicId());
        };
    }
}
