package com.zirocraft.zirologistics.wms.security.mfa.infrastructure.config;

import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaReauthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WmsTenantMfaReauthenticationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WmsTenantMfaReauthenticationConfiguration.class);

    @Test
    void reauthenticationCompositionIsDisabledByDefault() {
        contextRunner.run(context ->
                assertFalse(context.containsBean("wmsTenantMfaReauthenticationService")));
    }

    @Test
    void reauthenticationFlagAloneCannotEnableCompositionWithoutTenantMfaMasterFlag() {
        contextRunner
                .withPropertyValues("wms.security.tenant-mfa.reauthentication.enabled=true")
                .run(context -> {
                    assertFalse(context.containsBean("wmsTenantMfaReauthenticationService"));
                    assertTrue(context.getBeansOfType(WmsTenantMfaReauthenticationService.class).isEmpty());
                });
    }
}
