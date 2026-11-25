package com.zirocraft.zirologistics.wms.platform.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class PlatformJwtTokenServiceTest {
    private JwtEncoder encoder;
    private PlatformJwtProperties properties;
    private PlatformJwtTokenService service;
    private ArgumentCaptor<JwtEncoderParameters> captor;

    @BeforeEach
    void setUp() {
        encoder = mock(JwtEncoder.class);
        Jwt signed = mock(Jwt.class);
        when(signed.getTokenValue()).thenReturn("platform.signed.token");
        when(encoder.encode(any(JwtEncoderParameters.class))).thenReturn(signed);
        properties = new PlatformJwtProperties();
        properties.setIssuer("https://control.zirologistics.test");
        properties.setAudience("zirologistics-platform-api");
        properties.setKeyId("platform-key-1");
        service = new PlatformJwtTokenService(encoder, properties,
                Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC));
        captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
    }

    @Test
    void issuesShortLivedMfaVerifiedPlatformTokenWithoutTenantClaims() {
        Instant mfaAt = Instant.parse("2026-11-25T11:58:00Z");
        assertThat(service.issueAccessToken("operator-public-id", 7L, mfaAt))
                .isEqualTo("platform.signed.token");
        verify(encoder).encode(captor.capture());
        var parameters = captor.getValue();
        var claims = parameters.getClaims();
        assertThat(parameters.getJwsHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(parameters.getJwsHeader().getKeyId()).isEqualTo("platform-key-1");
        assertThat(claims.getIssuer().toString()).isEqualTo("https://control.zirologistics.test");
        assertThat(claims.getAudience()).containsExactly("zirologistics-platform-api");
        assertThat(claims.getSubject()).isEqualTo("operator-public-id");
        assertThat(claims.getClaimAsString("token_use")).isEqualTo("platform_access");
        assertThat(claims.getClaims()).containsEntry("cv", 7L).containsEntry("mfa", true);
        assertThat(claims.getClaim("mfa_at")).isEqualTo(mfaAt);
        assertThat(claims.getId()).isNotBlank();
        assertThat(Duration.between(claims.getIssuedAt(), claims.getExpiresAt())).isEqualTo(Duration.ofMinutes(10));
        assertThat(claims.getClaims()).doesNotContainKeys("mid", "tenant_id", "membership_id", "roles", "permissions");
    }

    @Test
    void rejectsMissingOrStaleMfaBeforeSigning() {
        assertThatThrownBy(() -> service.issueAccessToken("operator-public-id", 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueAccessToken("operator-public-id", 1L,
                Instant.parse("2026-11-25T11:54:59Z"))).isInstanceOf(IllegalArgumentException.class);
        verify(encoder, org.mockito.Mockito.never()).encode(any());
    }

    @Test
    void rejectsFutureMfaAndInvalidOperatorOrCredentialVersion() {
        assertThatThrownBy(() -> service.issueAccessToken("operator-public-id", 1L,
                Instant.parse("2026-11-25T12:00:01Z"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueAccessToken(" ", 1L,
                Instant.parse("2026-11-25T11:59:00Z"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.issueAccessToken("operator-public-id", -1L,
                Instant.parse("2026-11-25T11:59:00Z"))).isInstanceOf(IllegalArgumentException.class);
        verify(encoder, org.mockito.Mockito.never()).encode(any());
    }
}
