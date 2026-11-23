package com.zirocraft.zirologistics.wms.replenishment.http;

import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import com.zirocraft.zirologistics.wms.replenishment.application.WmsReplenishmentPlanningService;
import com.zirocraft.zirologistics.wms.replenishment.application.WmsReplenishmentTaskService;
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

@WebMvcTest(controllers = {WmsReplenishmentPlanningController.class, WmsReplenishmentTaskController.class})
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsReplenishmentPlanningController.class,
        WmsReplenishmentTaskController.class, WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {"wms.security.jwt.enabled=true","wms.security.http.auth-endpoints-enabled=false"})
class WmsReplenishmentHttpBoundaryTest {
    @Autowired MockMvc mockMvc;
    @MockBean(name="wmsAccessJwtDecoder") JwtDecoder accessJwtDecoder;
    @MockBean(name="wmsTenantSelectionJwtDecoder") JwtDecoder tenantSelectionDecoder;
    @MockBean WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean WmsReplenishmentPlanningService planningService;
    @MockBean WmsReplenishmentTaskService taskService;

    @Test void rulesListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/replenishment-rules"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(planningService);
    }
    @Test void rulesDetailRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/replenishment-rules/rule-id"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(planningService);
    }
    @Test void tasksListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/replenishment-tasks"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(taskService);
    }
    @Test void taskDetailRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/replenishment-tasks/task-id"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(taskService);
    }
}