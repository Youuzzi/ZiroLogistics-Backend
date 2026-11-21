package com.zirocraft.zirologistics.wms.security.config;

import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsMalformedJwtHttpRuntimeTest.ProbeController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsMalformedJwtHttpRuntimeTest.ProbeController.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=false"
})
class WmsMalformedJwtHttpRuntimeTest {

    private static final AtomicInteger PROTECTED_INVOCATIONS = new AtomicInteger();

    @Autowired private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;

    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;

    @BeforeEach
    void resetProbe() {
        PROTECTED_INVOCATIONS.set(0);
    }

    @Test
    void malformedSubjectMembershipAndPurposeClaimsAreRejectedAs401WithoutInvokingRoute() throws Exception {
        Instant now = Instant.now();
        Jwt missingSubject = Jwt.withTokenValue("missing-subject")
                .header("alg", "RS256").issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("mid", "membership-public-id").claim("token_use", "access").build();
        Jwt missingMembership = Jwt.withTokenValue("missing-membership")
                .header("alg", "RS256").subject("user-public-id")
                .issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("token_use", "access").build();
        Jwt wrongPurpose = Jwt.withTokenValue("wrong-purpose")
                .header("alg", "RS256").subject("user-public-id")
                .issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("mid", "membership-public-id").claim("token_use", "tenant_selection").build();

        when(accessJwtDecoder.decode("missing-subject")).thenReturn(missingSubject);
        when(accessJwtDecoder.decode("missing-membership")).thenReturn(missingMembership);
        when(accessJwtDecoder.decode("wrong-purpose")).thenReturn(wrongPurpose);
        when(authenticationConverter.convert(any(Jwt.class)))
                .thenThrow(new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token", "Invalid bearer token", null)));

        for (String token : new String[]{"missing-subject", "missing-membership", "wrong-purpose"}) {
            mockMvc.perform(get("/wms/malformed-jwt-probe")
                            .header("Authorization", "Bearer " + token)
                            .accept(MediaType.TEXT_PLAIN))
                    .andExpect(status().isUnauthorized());
        }

        verify(accessJwtDecoder, times(3)).decode(anyString());
        verify(authenticationConverter, times(3)).convert(any(Jwt.class));
        org.assertj.core.api.Assertions.assertThat(PROTECTED_INVOCATIONS.get()).isZero();
    }

    @RestController
    static class ProbeController {
        @GetMapping(value = "/wms/malformed-jwt-probe", produces = MediaType.TEXT_PLAIN_VALUE)
        String probe() {
            PROTECTED_INVOCATIONS.incrementAndGet();
            return "must-not-run";
        }
    }
}
