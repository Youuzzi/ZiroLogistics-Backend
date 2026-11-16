package com.zirocraft.zirologistics.wms.security.config;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsHttpSecurityEnabledTest.ProbeController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsHttpSecurityEnabledTest.ProbeController.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=true"
})
class WmsHttpSecurityEnabledTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;

    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;

    @Test
    void rejectsRequestsWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/wms/security-probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void permitsOnlyExactPublicPostAuthenticationRoutes() throws Exception {
        mockMvc.perform(post("/api/wms/v1/auth/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("wms-public-post"));

        mockMvc.perform(get("/api/wms/v1/auth/credentials"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/wms/v1/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsOnlyBearerTokensResolvedByWmsAuthenticationConverter() throws Exception {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("valid-test-token")
                .header("alg", "RS256")
                .subject("user-public-id")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("mid", "membership-public-id")
                .build();

        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                "user-public-id", 10L, "tenant-public-id",
                20L, "membership-public-id", Set.of("inventory.balance.read"));
        WmsJwtAuthenticationToken authentication = new WmsJwtAuthenticationToken(
                principal, Set.of(new SimpleGrantedAuthority("PERM_inventory.balance.read")));

        when(accessJwtDecoder.decode("valid-test-token")).thenReturn(jwt);
        when(authenticationConverter.convert(jwt)).thenReturn(authentication);

        mockMvc.perform(get("/wms/security-probe")
                        .header("Authorization", "Bearer valid-test-token")
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andExpect(content().string("wms-protected"));
    }

    @RestController
    static class ProbeController {
        @PostMapping(value = "/api/wms/v1/auth/credentials", produces = MediaType.TEXT_PLAIN_VALUE)
        String publicPost() {
            return "wms-public-post";
        }

        @GetMapping(value = "/api/wms/v1/auth/credentials", produces = MediaType.TEXT_PLAIN_VALUE)
        String nonPublicGet() {
            return "wms-not-public-get";
        }

        @GetMapping(value = "/wms/security-probe", produces = MediaType.TEXT_PLAIN_VALUE)
        String probe() {
            return "wms-protected";
        }
    }
}
