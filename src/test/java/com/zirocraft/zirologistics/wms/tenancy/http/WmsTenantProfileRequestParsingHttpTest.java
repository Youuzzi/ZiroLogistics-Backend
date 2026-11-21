package com.zirocraft.zirologistics.wms.tenancy.http;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsTenantProfileController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsTenantProfileController.class,
        WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=true"
})
class WmsTenantProfileRequestParsingHttpTest {
    private static final String ROUTE = "/api/wms/v1/tenants/current/profile";

    @Autowired private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;
    @MockBean(name = "wmsTenantSelectionJwtDecoder")
    private JwtDecoder tenantSelectionDecoder;
    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean
    private WmsTenantProfileService profileService;

    @Test
    void malformedJsonIsRejectedBeforeTheProfileServiceRuns() throws Exception {
        mockMvc.perform(put(ROUTE)
                        .with(authentication(authenticated(principal())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"legalName\": "))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(profileService);
    }

    @Test
    void unsupportedContentTypeIsRejectedBeforeTheProfileServiceRuns() throws Exception {
        mockMvc.perform(put(ROUTE)
                        .with(authentication(authenticated(principal())))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(profileService);
    }

    @Test
    void unsupportedDeleteMethodDoesNotInvokeTheProfileService() throws Exception {
        mockMvc.perform(delete(ROUTE)
                        .with(authentication(authenticated(principal()))))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(profileService);
    }

    private static UsernamePasswordAuthenticationToken authenticated(WmsAuthenticatedPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static WmsAuthenticatedPrincipal principal() {
        return new WmsAuthenticatedPrincipal(
                "user-public-id", 11L, "tenant-public-id", 21L, "membership-public-id", Set.of());
    }
}
