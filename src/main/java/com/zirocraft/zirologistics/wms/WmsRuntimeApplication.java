package com.zirocraft.zirologistics.wms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Independent WMS application entry point.
 *
 * Its component, entity, and repository scans are intentionally restricted to
 * the WMS namespace so legacy controllers, services, entities, and repositories
 * cannot be activated accidentally by this runtime.
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
