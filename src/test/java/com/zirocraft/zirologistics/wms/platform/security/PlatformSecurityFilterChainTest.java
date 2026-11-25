package com.zirocraft.zirologistics.wms.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PlatformSecurityFilterChainTest.ProbeController.class)
@ContextConfiguration(classes = PlatformSecurityTestApplication.class)
@Import({PlatformSecurityConfiguration.class, ProbeController.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.platform.security.jwt.enabled=true",
        "wms.security.jwt.enabled=false"
})
class PlatformSecurityFilterChainTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "platformJwtDecoder")
    private JwtDecoder platformJwtDecoder;

    @MockBean
    private PlatformJwtAuthenticationConverter platformAuthenticationConverter;

    @Test
    void requiresBearerAuthenticationOnEveryPlatformRoute() throws Exception {
        mockMvc.perform(get("/api/platform/v1/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usesPlatformDecoderAndConverterForPlatformRoutes() throws Exception {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("platform-token")
                .header("alg", "RS256")
                .header("kid", "platform-key")
                .subject("operator-public-id")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .claim("token_use", "platform_access")
                .claim("cv", 1L)
                .claim("mfa", true)
                .claim("mfa_at", now)
                .build();
        when(platformJwtDecoder.decode("platform-token")).thenReturn(jwt);
        when(platformAuthenticationConverter.convert(jwt))
                .thenReturn(org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated(new PlatformAuthenticatedPrincipal("operator-public-id"), null,
                                java.util.List.of()));

        mockMvc.perform(get("/api/platform/v1/probe").header("Authorization", "Bearer platform-token"))
                .andExpect(status().isOk());
    }

    @Test
    void platformMatcherDoesNotMakeTenantOrUnmatchedRoutesPublic() throws Exception {
        mockMvc.perform(get("/api/wms/v1/probe"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/unmatched/probe"))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class ProbeController {
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
}
