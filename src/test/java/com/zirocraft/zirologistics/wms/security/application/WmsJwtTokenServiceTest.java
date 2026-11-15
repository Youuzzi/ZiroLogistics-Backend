package com.zirocraft.zirologistics.wms.security.application;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.config.WmsJwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class WmsJwtTokenServiceTest {

    private JwtEncoder jwtEncoder;
    private WmsMembershipAccessService membershipAccessService;
    private WmsJwtTokenService tokenService;
    private ArgumentCaptor<JwtEncoderParameters> parametersCaptor;

    @BeforeEach
    void setUp() {
        jwtEncoder = mock(JwtEncoder.class);
        Jwt encodedJwt = mock(Jwt.class);
        when(encodedJwt.getTokenValue()).thenReturn("signed.jwt.token");
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(encodedJwt);

        WmsJwtProperties properties = new WmsJwtProperties();
        properties.setIssuer("https://auth.zirologistics.test");
        properties.setTenantSelectionAudience("zirologistics-wms-selection");
        properties.setAccessAudience("zirologistics-wms-api");
        properties.setKeyId("wms-test-key-1");

        membershipAccessService = mock(WmsMembershipAccessService.class);
        tokenService = new WmsJwtTokenService(jwtEncoder, properties, membershipAccessService);
        parametersCaptor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
    }

    @Test
    void issuesShortLivedTenantSelectionTokenWithoutMembershipOrPermissionClaims() {
        String token = tokenService.issueTenantSelectionToken("user-public-id");

        assertThat(token).isEqualTo("signed.jwt.token");
        verify(jwtEncoder).encode(parametersCaptor.capture());

        JwtEncoderParameters parameters = parametersCaptor.getValue();
        assertThat(parameters.getJwsHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(parameters.getJwsHeader().getKeyId()).isEqualTo("wms-test-key-1");

        var claims = parameters.getClaims();
        assertThat(claims.getIssuer().toString()).isEqualTo("https://auth.zirologistics.test");
        assertThat(claims.getSubject()).isEqualTo("user-public-id");
        assertThat(claims.getAudience()).containsExactly("zirologistics-wms-selection");
        assertThat(claims.getClaimAsString("token_use")).isEqualTo("tenant_selection");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getNotBefore()).isNotNull();
        assertThat(Duration.between(claims.getIssuedAt(), claims.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(claims.getClaims()).doesNotContainKeys("mid", "roles", "permissions");
    }

    @Test
    void issuesTenantBoundAccessTokenFromResolvedMembershipWithoutEmbeddingPermissions() {
        WmsMembershipAccess access = new WmsMembershipAccess(
                10L, "user-public-id", 20L, "tenant-public-id",
                30L, "membership-public-id", Set.of("inventory.balance.read"));

        when(membershipAccessService.resolve("user-public-id", "membership-public-id"))
                .thenReturn(access);

        String token = tokenService.issueAccessToken("user-public-id", "membership-public-id");
        verify(membershipAccessService).resolve("user-public-id", "membership-public-id");

        assertThat(token).isEqualTo("signed.jwt.token");
        verify(jwtEncoder).encode(parametersCaptor.capture());

        JwtEncoderParameters parameters = parametersCaptor.getValue();
        var claims = parameters.getClaims();
        assertThat(parameters.getJwsHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(claims.getSubject()).isEqualTo("user-public-id");
        assertThat(claims.getAudience()).containsExactly("zirologistics-wms-api");
        assertThat(claims.getClaimAsString("token_use")).isEqualTo("access");
        assertThat(claims.getClaimAsString("mid")).isEqualTo("membership-public-id");
        assertThat(claims.getId()).isNotBlank();
        assertThat(Duration.between(claims.getIssuedAt(), claims.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(claims.getClaims()).doesNotContainKeys("roles", "permissions", "tenant_id");
    }

    @Test
    void rejectsBlankUserAndMissingResolvedMembership() {
        assertThatThrownBy(() -> tokenService.issueTenantSelectionToken(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokenService.issueAccessToken("user-public-id", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
