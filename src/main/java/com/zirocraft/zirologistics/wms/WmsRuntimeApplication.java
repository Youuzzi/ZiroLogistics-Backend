package com.zirocraft.zirologistics.wms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Independent WMS application entry point.
 *
 * Component, entity, and repository scanning are intentionally restricted to
 * the WMS namespace. The WMS runtime uses its own database environment variables
 * and migration location; it must never inherit the legacy DB configuration.
 */
@Profile("wms")
@SpringBootApplication(scanBasePackages = "com.zirocraft.zirologistics.wms")
@EntityScan(basePackages = "com.zirocraft.zirologistics.wms")
@EnableJpaRepositories(basePackages = "com.zirocraft.zirologistics.wms")
public class WmsRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WmsRuntimeApplication.class);
        application.setAdditionalProfiles("wms");
        application.run(args);
    }
}
