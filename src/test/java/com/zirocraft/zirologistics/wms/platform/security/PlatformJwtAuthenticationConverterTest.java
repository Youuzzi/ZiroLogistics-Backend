package com.zirocraft.zirologistics.wms.platform.security;

import com.zirocraft.zirologistics.wms.platform.identity.domain.PlatformOperatorStatus;
import com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence.PlatformOperatorEntity;
import com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence.PlatformOperatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformJwtAuthenticationConverterTest {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private PlatformOperatorRepository repository;
    private PlatformJwtAuthenticationConverter converter;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformOperatorRepository.class);
        converter = new PlatformJwtAuthenticationConverter(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void authenticatesOnlyCurrentActiveVerifiedOperatorAndGrantsNoImplicitPermissions() {
        PlatformOperatorEntity operator = operator(PlatformOperatorStatus.ACTIVE, 9L, true);
        when(repository.findByPublicId("operator-public-id")).thenReturn(Optional.of(operator));

        AbstractAuthenticationToken authentication = converter.convert(jwt(
                "operator-public-id", 9L, Instant.parse("2026-11-25T11:59:00Z")));

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal())
                .isEqualTo(new PlatformAuthenticatedPrincipal("operator-public-id"));
        assertThat(authentication.getAuthorities()).isEmpty();
    }

    @Test
    void rejectsUnknownInactiveUnverifiedAndCredentialVersionChangedOperators() {
        when(repository.findByPublicId("missing")).thenReturn(Optional.empty());
        when(repository.findByPublicId("suspended"))
                .thenReturn(Optional.of(operator(PlatformOperatorStatus.SUSPENDED, 9L, true)));
        when(repository.findByPublicId("unverified"))
                .thenReturn(Optional.of(operator(PlatformOperatorStatus.ACTIVE, 9L, false)));
        when(repository.findByPublicId("rotated"))
                .thenReturn(Optional.of(operator(PlatformOperatorStatus.ACTIVE, 10L, true)));

        assertThatThrownBy(() -> converter.convert(jwt("missing", 9L, NOW.minusSeconds(30))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(jwt("suspended", 9L, NOW.minusSeconds(30))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(jwt("unverified", 9L, NOW.minusSeconds(30))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(jwt("rotated", 9L, NOW.minusSeconds(30))))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void rejectsMalformedClaimsAndExpiredMfaFreshnessWindow() {
        assertThatThrownBy(() -> converter.convert(jwt("operator-public-id", -1L, NOW.minusSeconds(30))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(jwt("operator-public-id", 9L, NOW.minusSeconds(901))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(jwtWithClaims(Map.of(
                "sub", "operator-public-id", "cv", 9L, "mfa", true, "mfa_at", NOW.plusSeconds(5)))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(jwtWithClaims(Map.of(
                "sub", "operator-public-id", "cv", 9.5d, "mfa", true, "mfa_at", NOW.minusSeconds(30)))))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    private static PlatformOperatorEntity operator(PlatformOperatorStatus status, long credentialVersion,
            boolean emailVerified) {
        PlatformOperatorEntity entity = new PlatformOperatorEntity();
        entity.setPublicId("operator-public-id");
        entity.setStatus(status);
        entity.setCredentialVersion(credentialVersion);
        entity.setEmailVerifiedAt(emailVerified ? LocalDateTime.of(2026, 1, 1, 0, 0) : null);
        return entity;
    }

    private static Jwt jwt(String subject, long credentialVersion, Instant mfaAt) {
        return jwtWithClaims(Map.of("sub", subject, "cv", credentialVersion, "mfa", true, "mfa_at", mfaAt));
    }

    private static Jwt jwtWithClaims(Map<String, Object> claims) {
        return new Jwt("token", NOW.minusSeconds(60), NOW.plusSeconds(60),
                Map.of("alg", "RS256", "kid", "platform-key"), claims);
    }
}
