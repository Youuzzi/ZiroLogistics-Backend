package com.zirocraft.zirologistics.wms.security.config;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsHttpSecurityEnabledTest.ProbeController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsJwtAuthenticationConverter.class,
        WmsHttpSecurityEnabledTest.ProbeController.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {"wms.security.jwt.enabled=true", "wms.security.http.auth-endpoints-enabled=true"})
class WmsJwtClaimsHttpSecurityTest {
    private static final String ROUTE = "/wms/security-probe";
    @Autowired private MockMvc mockMvc;
    @MockBean(name = "wmsAccessJwtDecoder") private JwtDecoder accessJwtDecoder;
    @MockBean(name = "wmsTenantSelectionJwtDecoder") private JwtDecoder tenantSelectionDecoder;
    @MockBean private WmsMembershipAccessService membershipAccessService;

    @Test void missingSubjectIsRejectedAtProtectedHttpBoundary() throws Exception {
        Instant now=Instant.now();
        Jwt jwt=Jwt.withTokenValue("missing-subject").header("alg","RS256").issuedAt(now).expiresAt(now.plusSeconds(600))
            .claim("token_use","access").claim("cv",0L).claim("mid","membership-public-id").build();
        when(accessJwtDecoder.decode("missing-subject")).thenReturn(jwt);
        mockMvc.perform(get(ROUTE).header("Authorization","Bearer missing-subject")).andExpect(status().isUnauthorized());
        verifyNoInteractions(membershipAccessService);
    }
    @Test void missingMembershipClaimIsRejectedAtProtectedHttpBoundary() throws Exception {
        Instant now=Instant.now();
        Jwt jwt=Jwt.withTokenValue("missing-membership").header("alg","RS256").subject("user-public-id")
            .issuedAt(now).expiresAt(now.plusSeconds(600)).claim("token_use","access").claim("cv",0L).build();
        when(accessJwtDecoder.decode("missing-membership")).thenReturn(jwt);
        mockMvc.perform(get(ROUTE).header("Authorization","Bearer missing-membership")).andExpect(status().isUnauthorized());
        verifyNoInteractions(membershipAccessService);
    }
    @Test void wrongPurposeTokenIsRejectedAtProtectedHttpBoundary() throws Exception {
        Instant now=Instant.now();
        Jwt jwt=Jwt.withTokenValue("password-reset-purpose").header("alg","RS256").subject("user-public-id")
            .issuedAt(now).expiresAt(now.plusSeconds(600)).claim("token_use","password_reset").claim("cv",0L)
            .claim("mid","membership-public-id").build();
        when(accessJwtDecoder.decode("password-reset-purpose")).thenReturn(jwt);
        mockMvc.perform(get(ROUTE).header("Authorization","Bearer password-reset-purpose")).andExpect(status().isUnauthorized());
        verifyNoInteractions(membershipAccessService);
    }
    @Test void inactiveOrForeignMembershipClaimIsRejectedAtProtectedHttpBoundary() throws Exception {
        Instant now=Instant.now();
        Jwt jwt=Jwt.withTokenValue("foreign-membership").header("alg","RS256").subject("user-public-id")
            .issuedAt(now).expiresAt(now.plusSeconds(600)).claim("token_use","access").claim("cv",0L)
            .claim("mid","foreign-membership-public-id").build();
        when(accessJwtDecoder.decode("foreign-membership")).thenReturn(jwt);
        when(membershipAccessService.resolve("user-public-id","foreign-membership-public-id"))
            .thenThrow(new WmsMembershipAccessDeniedException());
        mockMvc.perform(get(ROUTE).header("Authorization","Bearer foreign-membership")).andExpect(status().isUnauthorized());
        verify(membershipAccessService).resolve("user-public-id","foreign-membership-public-id");
        verifyNoMoreInteractions(membershipAccessService);
    }
    @Test void nonStringMembershipClaimIsRejectedWithoutInternalServerError() throws Exception {
        Instant now=Instant.now();
        Jwt jwt=Jwt.withTokenValue("malformed-membership-claim").header("alg","RS256").subject("user-public-id")
            .issuedAt(now).expiresAt(now.plusSeconds(600)).claim("token_use","access").claim("cv",0L).claim("mid",42L).build();
        when(accessJwtDecoder.decode("malformed-membership-claim")).thenReturn(jwt);
        mockMvc.perform(get(ROUTE).header("Authorization","Bearer malformed-membership-claim")).andExpect(status().isUnauthorized());
        verify(membershipAccessService).resolve("user-public-id","42");
        verifyNoMoreInteractions(membershipAccessService);
    }
}