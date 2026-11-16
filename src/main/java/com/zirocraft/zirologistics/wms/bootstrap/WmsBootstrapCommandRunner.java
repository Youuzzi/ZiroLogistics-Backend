package com.zirocraft.zirologistics.wms.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Executes only when the explicit non-web command mode is supplied. */
@Component
@Profile("wms")
public class WmsBootstrapCommandRunner implements ApplicationRunner {
    private final WmsBootstrapProperties properties;
    private final WmsPlatformBootstrapService service;

    public WmsBootstrapCommandRunner(WmsBootstrapProperties properties, WmsPlatformBootstrapService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("wms.bootstrap.command-mode")) return;
        if (!args.getOptionValues("wms.bootstrap.command-mode").contains("true")) {
            throw new IllegalStateException("WMS bootstrap command mode must be explicitly true");
        }
        WmsPlatformBootstrapService.Result result = service.bootstrap(properties);
        // Only public identifiers are emitted; no email, password, hash, or token.
        System.out.println("WMS_BOOTSTRAP_COMPLETED");
        System.out.println("operationRef=" + result.operationRef());
        System.out.println("tenantPublicId=" + result.tenantPublicId());
        System.out.println("userPublicId=" + result.userPublicId());
        System.out.println("membershipPublicId=" + result.membershipPublicId());
        System.out.println("rolePublicId=" + result.rolePublicId());
    }
}
