package com.zirocraft.zirologistics.wms.identity.http;

import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxEncryptionService;
import com.zirocraft.zirologistics.wms.identity.application.WmsInvitationAcceptanceService;
import com.zirocraft.zirologistics.wms.identity.application.WmsTenantInvitationService;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
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
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.verifyNoInteractions;

@WebMvcTest(controllers = WmsInvitationController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsInvitationController.class, WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {"wms.security.jwt.enabled=true","wms.security.http.auth-endpoints-enabled=false"})
class WmsInvitationHttpAuthBoundaryTest {
    @Autowired MockMvc mockMvc;
    @MockBean(name="wmsAccessJwtDecoder") JwtDecoder accessJwtDecoder;
    @MockBean(name="wmsTenantSelectionJwtDecoder") JwtDecoder tenantSelectionDecoder;
    @MockBean WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean WmsTenantInvitationService invitationService;
    @MockBean WmsInvitationAcceptanceService acceptanceService;
    @MockBean WmsEmailOutboxEncryptionService encryptionService;

    @Test void invitationIssuanceRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/wms/v1/invitations")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"member@example.test\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(invitationService);
    }
    @Test void existingAccountAcceptanceRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/wms/v1/invitations/existing-account-acceptances")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actionToken\":\"opaque-token\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(acceptanceService);
    }
}