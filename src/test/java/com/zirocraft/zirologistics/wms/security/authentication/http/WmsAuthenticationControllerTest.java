package com.zirocraft.zirologistics.wms.security.authentication.http;

import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxEncryptionService;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailVerificationService;
import com.zirocraft.zirologistics.wms.identity.application.WmsPasswordManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtTokenService;
import com.zirocraft.zirologistics.wms.security.authentication.application.WmsCredentialAuthenticationException;
import com.zirocraft.zirologistics.wms.security.authentication.application.WmsCredentialAuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WmsAuthenticationControllerTest {

    private WmsJwtTokenService jwtTokenService;
    private JwtDecoder tenantSelectionDecoder;
    private WmsAuthenticationController controller;

    @BeforeEach
    void setUp() {
        jwtTokenService = mock(WmsJwtTokenService.class);
        tenantSelectionDecoder = mock(JwtDecoder.class);
        controller = new WmsAuthenticationController(
                mock(WmsCredentialAuthenticationService.class),
                jwtTokenService,
                tenantSelectionDecoder,
                mock(WmsPasswordManagementService.class),
                mock(WmsEmailVerificationService.class),
                mock(WmsEmailOutboxEncryptionService.class));
    }

    @Test
    void forwardsSelectionTokenCredentialVersionWhenIssuingAccessToken() {
        Instant now = Instant.now();
        Jwt selection = Jwt.withTokenValue("selection-token")
                .header("alg", "RS256")
                .subject("user-public-id")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("token_use", "tenant_selection")
                .claim("cv", 4L)
                .build();
        when(tenantSelectionDecoder.decode("selection-token")).thenReturn(selection);
        when(jwtTokenService.issueAccessToken("user-public-id", "membership-public-id", 4L))
                .thenReturn("access-token");

        WmsAuthenticationController.AccessTokenResponse response = controller.selectTenant(
                new WmsAuthenticationController.TenantSelectionRequest(
                        "selection-token", "membership-public-id"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        verify(jwtTokenService).issueAccessToken("user-public-id", "membership-public-id", 4L);
    }

    @Test
    void rejectsWrongSelectionTokenPurposeBeforeIssuingAccessToken() {
        Instant now = Instant.now();
        Jwt selection = Jwt.withTokenValue("wrong-purpose")
                .header("alg", "RS256")
                .subject("user-public-id")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("token_use", "access")
                .claim("cv", 0L)
                .build();
        when(tenantSelectionDecoder.decode("wrong-purpose")).thenReturn(selection);

        assertThatThrownBy(() -> controller.selectTenant(
                new WmsAuthenticationController.TenantSelectionRequest(
                        "wrong-purpose", "membership-public-id")))
                .isInstanceOf(WmsCredentialAuthenticationException.class);
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void rejectsSelectionTokenWithoutCredentialVersionBeforeIssuingAccessToken() {
        Instant now = Instant.now();
        Jwt selection = Jwt.withTokenValue("missing-version")
                .header("alg", "RS256")
                .subject("user-public-id")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("token_use", "tenant_selection")
                .build();
        when(tenantSelectionDecoder.decode("missing-version")).thenReturn(selection);

        assertThatThrownBy(() -> controller.selectTenant(
                new WmsAuthenticationController.TenantSelectionRequest(
                        "missing-version", "membership-public-id")))
                .isInstanceOf(WmsCredentialAuthenticationException.class);
        verifyNoInteractions(jwtTokenService);
    }
}
