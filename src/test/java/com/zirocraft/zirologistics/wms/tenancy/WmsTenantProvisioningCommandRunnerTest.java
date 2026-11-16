package com.zirocraft.zirologistics.wms.tenancy;

import com.zirocraft.zirologistics.wms.identity.application.WmsEmailVerificationService;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProvisioningResult;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProvisioningService;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.config.WmsTenantProvisioningCommandRunner;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.config.WmsTenantProvisioningProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class WmsTenantProvisioningCommandRunnerTest {
    @Test
    void ordinaryRuntimeDoesNotRunProvisioningCommand() throws Exception {
        WmsTenantProvisioningProperties properties = new WmsTenantProvisioningProperties();
        WmsTenantProvisioningService provisioning = mock(WmsTenantProvisioningService.class);
        WmsEmailVerificationService verification = mock(WmsEmailVerificationService.class);
        WmsTenantProvisioningCommandRunner runner =
                new WmsTenantProvisioningCommandRunner(properties, provisioning, verification);

        runner.run(new DefaultApplicationArguments("--spring.profiles.active=wms"));

        verifyNoInteractions(provisioning, verification);
    }

    @Test
    void explicitCommandRequiresEnablement() {
        WmsTenantProvisioningProperties properties = new WmsTenantProvisioningProperties();
        WmsTenantProvisioningService provisioning = mock(WmsTenantProvisioningService.class);
        WmsEmailVerificationService verification = mock(WmsEmailVerificationService.class);
        WmsTenantProvisioningCommandRunner runner =
                new WmsTenantProvisioningCommandRunner(properties, provisioning, verification);

        assertThrows(IllegalStateException.class, () ->
                runner.run(new DefaultApplicationArguments("--wms.tenant.provisioning.command-mode=true")));

        verifyNoInteractions(provisioning, verification);
    }

    @Test
    void pendingOwnerGetsEmailVerificationRequestedAfterProvisioning() throws Exception {
        WmsTenantProvisioningProperties properties = new WmsTenantProvisioningProperties();
        properties.setEnabled(true);
        properties.setOwnerEmail("new-owner@example.test");
        WmsTenantProvisioningService provisioning = mock(WmsTenantProvisioningService.class);
        WmsEmailVerificationService verification = mock(WmsEmailVerificationService.class);
        WmsTenantProvisioningResult result = new WmsTenantProvisioningResult(
                "a4b08a7b-43e1-4a11-9e65-b1efac7dd3b9",
                "9d0ed65a-71d5-4cb1-8f84-6dfb4b1acb7a",
                "TENANT_A",
                "15de27ea-48aa-48f7-b79b-06b1a4c06c56",
                "2c17c2bb-0c0d-44f5-9e10-f4a4b52a14e5",
                "0d8bc3e7-cb5d-4df4-8f65-8a54dbfcb04b",
                true,
                false);
        when(provisioning.provision(properties)).thenReturn(result);
        WmsTenantProvisioningCommandRunner runner =
                new WmsTenantProvisioningCommandRunner(properties, provisioning, verification);

        runner.run(new DefaultApplicationArguments("--wms.tenant.provisioning.command-mode=true"));

        verify(provisioning).provision(properties);
        verify(verification).requestEmailVerification("new-owner@example.test");
    }

    @Test
    void existingVerifiedOwnerDoesNotReceiveNewVerificationRequest() throws Exception {
        WmsTenantProvisioningProperties properties = new WmsTenantProvisioningProperties();
        properties.setEnabled(true);
        WmsTenantProvisioningService provisioning = mock(WmsTenantProvisioningService.class);
        WmsEmailVerificationService verification = mock(WmsEmailVerificationService.class);
        when(provisioning.provision(properties)).thenReturn(new WmsTenantProvisioningResult(
                "a4b08a7b-43e1-4a11-9e65-b1efac7dd3b9",
                "9d0ed65a-71d5-4cb1-8f84-6dfb4b1acb7a",
                "TENANT_A",
                "15de27ea-48aa-48f7-b79b-06b1a4c06c56",
                "2c17c2bb-0c0d-44f5-9e10-f4a4b52a14e5",
                "0d8bc3e7-cb5d-4df4-8f65-8a54dbfcb04b",
                false,
                false));
        WmsTenantProvisioningCommandRunner runner =
                new WmsTenantProvisioningCommandRunner(properties, provisioning, verification);

        runner.run(new DefaultApplicationArguments("--wms.tenant.provisioning.command-mode=true"));

        verify(provisioning).provision(properties);
        verifyNoInteractions(verification);
    }
}
