package com.zirocraft.zirologistics.wms.platform.security;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlatformSecurityProbeController {
    @GetMapping("/api/platform/v1/probe")
    String platformProbe(Authentication authentication) {
        return authentication.getName();
    }

    @GetMapping("/api/wms/v1/probe")
    String tenantProbe() {
        return "tenant";
    }

    @GetMapping("/unmatched/probe")
    String unmatchedProbe() {
        return "unmatched";
    }
}
