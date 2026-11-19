package com.zirocraft.zirologistics.wms.security.config;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationToken;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsPublicAuthEndpointsEnabledCondition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Conditional;
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

/**
 * Verifies that disabling public authentication endpoints does not unregister
 * the authenticated WMS business API.
 */
@WebMvcTest(controllers = WmsHttpSecurityAuthEndpointsDisabledTest.ProbeController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsHttpSecurityAuthEndpointsDisabledTest.ProbeController.class,
        WmsHttpSecurityAuthEndpointsDisabledTest.PublicAuthenticationProbeController.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=false"
})
class WmsHttpSecurityAuthEndpointsDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;

    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;

    @Test
    void keepsBusinessRoutesRegisteredAndProtectedWhenPublicAuthEndpointsAreDisabled() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/warehouses"))
                .andExpect(status().isUnauthorized());

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
                20L, "membership-public-id", Set.of("warehouse.read"));
        WmsJwtAuthenticationToken authentication = new WmsJwtAuthenticationToken(
                principal, Set.of(new SimpleGrantedAuthority("PERM_warehouse.read")));
        when(accessJwtDecoder.decode("valid-test-token")).thenReturn(jwt);
        when(authenticationConverter.convert(jwt)).thenReturn(authentication);

        mockMvc.perform(get("/api/wms/v1/tenants/current/warehouses")
                        .header("Authorization", "Bearer valid-test-token")
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk())
                .andExpect(content().string("wms-business-route"));
    }

    @Test
    void doesNotRegisterPublicAuthenticationRouteWhenPublicAuthEndpointsAreDisabled() throws Exception {
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
                20L, "membership-public-id", Set.of("warehouse.read"));
        WmsJwtAuthenticationToken authentication = new WmsJwtAuthenticationToken(
                principal, Set.of(new SimpleGrantedAuthority("PERM_warehouse.read")));
        when(accessJwtDecoder.decode("valid-test-token")).thenReturn(jwt);
        when(authenticationConverter.convert(jwt)).thenReturn(authentication);

        mockMvc.perform(post("/api/wms/v1/auth/credentials")
                        .header("Authorization", "Bearer valid-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @RestController
    @Conditional(WmsHttpApiEnabledCondition.class)
    static class ProbeController {
        @GetMapping(value = "/api/wms/v1/tenants/current/warehouses", produces = MediaType.TEXT_PLAIN_VALUE)
        String businessRoute() {
            return "wms-business-route";
        }

    }

    @RestController
    @Conditional(WmsPublicAuthEndpointsEnabledCondition.class)
    static class PublicAuthenticationProbeController {
        @PostMapping(
                value = "/api/wms/v1/auth/credentials", produces = MediaType.TEXT_PLAIN_VALUE)
        String publicAuthenticationRoute() {
            return "public-auth-route";
        }
    }
}
