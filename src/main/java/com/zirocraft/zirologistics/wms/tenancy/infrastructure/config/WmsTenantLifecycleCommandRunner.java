package com.zirocraft.zirologistics.wms.tenancy.infrastructure.config;

import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleAction;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleResult;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@Profile("wms")
@ConditionalOnNotWebApplication
public class WmsTenantLifecycleCommandRunner implements ApplicationRunner {
    private final WmsTenantLifecycleProperties properties;
    private final WmsTenantLifecycleService service;

    public WmsTenantLifecycleCommandRunner(
            WmsTenantLifecycleProperties properties,
            WmsTenantLifecycleService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("wms.tenant.lifecycle.command-mode")) return;
        if (!args.getOptionValues("wms.tenant.lifecycle.command-mode").contains("true")
                || !properties.isEnabled()) {
            throw new IllegalStateException("WMS tenant lifecycle command is not explicitly enabled");
        }

        final WmsTenantLifecycleAction action;
        try {
            action = WmsTenantLifecycleAction.valueOf(properties.getAction().trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("WMS tenant lifecycle action is invalid");
        }

        WmsTenantLifecycleResult result = service.transition(
                action, properties.getTenantPublicId(), properties.getReason(),
                properties.getOperationRef(), properties.getConfirmation());
        System.out.println("WMS_TENANT_LIFECYCLE_COMPLETED");
        System.out.println("operationRef=" + result.operationRef());
        System.out.println("tenantPublicId=" + result.tenantPublicId());
        System.out.println("tenantCode=" + result.tenantCode());
        System.out.println("previousStatus=" + result.previousStatus());
        System.out.println("currentStatus=" + result.currentStatus());
        System.out.println("alreadyApplied=" + result.alreadyApplied());
    }
}
