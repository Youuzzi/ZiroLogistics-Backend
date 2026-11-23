package com.zirocraft.zirologistics.wms.security.authentication.http;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.identity.application.EmailVerificationRequestResult;
import com.zirocraft.zirologistics.wms.identity.application.PasswordResetRequestResult;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxEncryptionService;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailVerificationService;
import com.zirocraft.zirologistics.wms.identity.application.WmsPasswordManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtTokenService;
import com.zirocraft.zirologistics.wms.security.authentication.application.WmsCredentialAuthenticationResult;
import com.zirocraft.zirologistics.wms.security.authentication.application.WmsCredentialAuthenticationService;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = WmsAuthenticationController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsAuthenticationController.class,
        WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=true"
})
class WmsAuthenticationControllerHttpTest {

    @Autowired private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;
    @MockBean(name = "wmsTenantSelectionJwtDecoder")
    private JwtDecoder tenantSelectionDecoder;
    @MockBean
    private com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean private WmsCredentialAuthenticationService credentialAuthentication;
    @MockBean private WmsJwtTokenService jwtTokenService;
    @MockBean private WmsPasswordManagementService passwordManagement;
    @MockBean private WmsEmailVerificationService emailVerification;
    @MockBean private WmsEmailOutboxEncryptionService emailEncryption;

    @Test
    void credentialsEndpointReturnsTenantSelectionAndUsesSocketPeerForThrottleIdentity() throws Exception {
        when(credentialAuthentication.authenticate(
                eq("person@example.test"), eq("StrongPassword123"),
                eq("203.0.113.8"), eq("198.51.100.2")))
                .thenReturn(new WmsCredentialAuthenticationResult(
                        "selection-token",
                        List.of(new WmsCredentialAuthenticationResult.SelectableMembership(
                                "membership-public-id", "tenant-public-id", "TENANT", "Tenant Display"))));

        mockMvc.perform(post("/api/wms/v1/auth/credentials")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.8");
                            return request;
                        })
                        .header("X-Forwarded-For", "198.51.100.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"person@example.test","password":"StrongPassword123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantSelectionToken").value("selection-token"))
                .andExpect(jsonPath("$.memberships[0].membershipPublicId").value("membership-public-id"));

        verify(credentialAuthentication).authenticate(
                "person@example.test", "StrongPassword123", "203.0.113.8", "198.51.100.2");
    }

    @Test
    void tenantSelectionRequiresValidatedSelectionTokenAndIssuesAccessToken() throws Exception {
        Instant now = Instant.now();
        Jwt selection = Jwt.withTokenValue("selection-token")
                .header("alg", "RS256")
                .subject("user-public-id")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("token_use", "tenant_selection")
                .claim("cv", 0L)
                .build();
        when(tenantSelectionDecoder.decode("selection-token")).thenReturn(selection);
        when(jwtTokenService.issueAccessToken("user-public-id", "membership-public-id", 0L))
                .thenReturn("access-token");

        mockMvc.perform(post("/api/wms/v1/auth/tenant-selection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSelectionToken":"selection-token","membershipPublicId":"membership-public-id"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void passwordResetRequestReturnsGenericAcceptedResponse() throws Exception {
        when(passwordManagement.requestPasswordReset("person@example.test"))
                .thenReturn(PasswordResetRequestResult.acceptedRequest());

        mockMvc.perform(post("/api/wms/v1/auth/password-reset-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"person@example.test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void invalidSelectionTokenReturnsGenericUnauthorizedError() throws Exception {
        when(tenantSelectionDecoder.decode("bad-token"))
                .thenThrow(new org.springframework.security.oauth2.jwt.BadJwtException("private decoder detail"));

        mockMvc.perform(post("/api/wms/v1/auth/tenant-selection")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantSelectionToken":"bad-token","membershipPublicId":"membership-public-id"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Authentication failed"));
    }
}
