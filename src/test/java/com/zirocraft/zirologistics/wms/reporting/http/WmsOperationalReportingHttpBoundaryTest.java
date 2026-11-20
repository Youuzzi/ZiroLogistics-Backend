package com.zirocraft.zirologistics.wms.reporting.http;

import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import com.zirocraft.zirologistics.wms.reporting.application.WmsOperationalReportingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.Mockito.verifyNoInteractions;

@WebMvcTest(controllers = WmsOperationalReportingController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsOperationalReportingController.class, WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {"wms.security.jwt.enabled=true","wms.security.http.auth-endpoints-enabled=false"})
class WmsOperationalReportingHttpBoundaryTest {
    @Autowired MockMvc mockMvc;
    @MockBean(name="wmsAccessJwtDecoder") JwtDecoder accessJwtDecoder;
    @MockBean(name="wmsTenantSelectionJwtDecoder") JwtDecoder tenantSelectionDecoder;
    @MockBean WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean WmsOperationalReportingService service;

    @Test void operationalSummaryRequiresAuthenticationAndNeverCallsService() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/reports/operational-summary"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}