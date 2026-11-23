package com.zirocraft.zirologistics.wms.security.config;

import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsHttpSecurityEnabledTest.ProbeController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsHttpSecurityEnabledTest.ProbeController.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=true"
})
class WmsCsrfBoundaryHttpTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;

    @MockBean(name = "wmsTenantSelectionJwtDecoder")
    private JwtDecoder tenantSelectionDecoder;

    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;

    @Test
    void statelessPublicWmsAuthenticationPostDoesNotRequireBrowserCsrfToken() throws Exception {
        mockMvc.perform(post("/api/wms/v1/auth/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void csrfProtectionRemainsEnabledOutsideTheStatelessWmsApiNamespace() throws Exception {
        mockMvc.perform(post("/csrf-protected-probe"))
                .andExpect(status().isForbidden());
    }
}
