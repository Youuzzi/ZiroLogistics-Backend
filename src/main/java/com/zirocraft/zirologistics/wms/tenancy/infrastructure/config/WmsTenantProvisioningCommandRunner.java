package com.zirocraft.zirologistics.wms.tenancy.infrastructure.config;

import com.zirocraft.zirologistics.wms.identity.application.WmsEmailVerificationService;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProvisioningResult;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProvisioningService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("wms")
@ConditionalOnNotWebApplication
public class WmsTenantProvisioningCommandRunner implements ApplicationRunner {
    private final WmsTenantProvisioningProperties properties;
    private final WmsTenantProvisioningService provisioningService;
    private final WmsEmailVerificationService emailVerificationService;

    public WmsTenantProvisioningCommandRunner(
            WmsTenantProvisioningProperties properties,
            WmsTenantProvisioningService provisioningService,
            WmsEmailVerificationService emailVerificationService) {
        this.properties = properties;
        this.provisioningService = provisioningService;
        this.emailVerificationService = emailVerificationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("wms.tenant.provisioning.command-mode")) return;
        if (!args.getOptionValues("wms.tenant.provisioning.command-mode").contains("true")
                || !properties.isEnabled()) {
            throw new IllegalStateException("WMS tenant provisioning command is not explicitly enabled");
        }

        WmsTenantProvisioningResult result = provisioningService.provision(properties);
        if (result.ownerVerificationRequired()) {
            // The provisioning transaction commits first. Retrying with the same operation
            // reference safely reuses the tenant and re-issues a fresh verification email.
            emailVerificationService.requestEmailVerification(properties.getOwnerEmail());
        }

        System.out.println("WMS_TENANT_PROVISIONING_COMPLETED");
        System.out.println("operationRef=" + result.operationRef());
        System.out.println("tenantPublicId=" + result.tenantPublicId());
        System.out.println("tenantCode=" + result.tenantCode());
        System.out.println("ownerUserPublicId=" + result.ownerUserPublicId());
        System.out.println("ownerMembershipPublicId=" + result.ownerMembershipPublicId());
        System.out.println("ownerRolePublicId=" + result.ownerRolePublicId());
        System.out.println("ownerVerificationRequired=" + result.ownerVerificationRequired());
        System.out.println("alreadyProvisioned=" + result.alreadyProvisioned());
    }
}
