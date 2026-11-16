package com.zirocraft.zirologistics.wms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.Arrays;

/**
 * Independent WMS application entry point.
 * Controlled bootstrap is a short-lived non-web process requiring an explicit
 * command-line switch. Ordinary WMS startup remains a web runtime.
 */
@Profile("wms")
@SpringBootApplication(scanBasePackages = "com.zirocraft.zirologistics.wms")
@EntityScan(basePackages = "com.zirocraft.zirologistics.wms")
@EnableJpaRepositories(basePackages = "com.zirocraft.zirologistics.wms")
public class WmsRuntimeApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WmsRuntimeApplication.class);
        application.setAdditionalProfiles("wms");
        boolean bootstrapCommand = Arrays.asList(args).contains("--wms.bootstrap.command-mode=true");
        if (bootstrapCommand) application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
