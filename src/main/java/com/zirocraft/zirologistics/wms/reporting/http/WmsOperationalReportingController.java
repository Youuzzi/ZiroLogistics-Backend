package com.zirocraft.zirologistics.wms.reporting.http;

import com.zirocraft.zirologistics.wms.reporting.application.WmsOperationalReportingService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current/reports")
@Conditional(WmsHttpApiEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsOperationalReportingController {
    private final WmsOperationalReportingService service;

    public WmsOperationalReportingController(WmsOperationalReportingService service) {
        this.service = service;
    }

    @GetMapping("/operational-summary")
    public Map<String, Object> operationalSummary(Authentication authentication) {
        Object candidate = authentication == null ? null : authentication.getPrincipal();
        if (!(candidate instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return service.operationalSummary(principal);
    }
}
