package com.zirocraft.zirologistics.wms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
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
        boolean tenantLifecycleCommand = Arrays.asList(args).contains("--wms.tenant.lifecycle.command-mode=true");
        boolean tenantProvisioningCommand = Arrays.asList(args).contains("--wms.tenant.provisioning.command-mode=true");
        int operatorCommandCount = (bootstrapCommand ? 1 : 0)
                + (tenantLifecycleCommand ? 1 : 0)
                + (tenantProvisioningCommand ? 1 : 0);
        if (operatorCommandCount > 1) {
            throw new IllegalArgumentException("Only one WMS operator command may run at a time");
        }
        if (operatorCommandCount == 1) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }
}
