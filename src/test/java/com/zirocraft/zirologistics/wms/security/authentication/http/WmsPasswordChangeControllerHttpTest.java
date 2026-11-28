package com.zirocraft.zirologistics.wms.security.authentication.http;

import com.zirocraft.zirologistics.wms.identity.application.WmsPasswordManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
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
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsPasswordChangeController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsPasswordChangeController.class,
        WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=false"
})
class WmsPasswordChangeControllerHttpTest {

    private static final String ROUTE = "/api/wms/v1/auth/password-changes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;

    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;

    @MockBean
    private WmsPasswordManagementService passwordManagement;

    @Test
    void passwordChangeRouteRemainsRegisteredWhenAnonymousAuthEndpointsAreDisabled() {
        boolean registered = handlerMapping.getHandlerMethods().keySet().stream()
                .anyMatch(mapping -> mapping.getMethodsCondition().getMethods().contains(RequestMethod.POST)
                        && mapping.getPatternValues().contains(ROUTE));

        assertThat(registered).isTrue();
    }

    @Test
    void passwordChangeStillRequiresAnAuthenticatedWmsAccessToken() throws Exception {
        mockMvc.perform(post(ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"CurrentPassword123","newPassword":"NewPassword456"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
