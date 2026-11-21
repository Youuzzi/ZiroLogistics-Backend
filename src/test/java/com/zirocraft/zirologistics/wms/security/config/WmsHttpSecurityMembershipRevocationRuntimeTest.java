package com.zirocraft.zirologistics.wms.security.config;

import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsHttpSecurityEnabledTest.ProbeController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsHttpSecurityEnabledTest.ProbeController.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=true"
})
class WmsHttpSecurityMembershipRevocationRuntimeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;

    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;

    @Test
    void deniesProtectedHttpRequestWhenMembershipResolutionRejectsExistingBearerToken() throws Exception {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("revoked-membership-token")
                .header("alg", "RS256")
                .subject("user-public-id")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("mid", "membership-public-id")
                .build();

        when(accessJwtDecoder.decode("revoked-membership-token")).thenReturn(jwt);
        when(authenticationConverter.convert(jwt)).thenThrow(
                new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token", "Invalid bearer token", null)));

        mockMvc.perform(get("/wms/security-probe")
                        .header("Authorization", "Bearer revoked-membership-token"))
                .andExpect(status().isUnauthorized());
    }
}
