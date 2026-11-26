package com.zirocraft.zirologistics.wms.platform.security;

import com.zirocraft.zirologistics.wms.platform.identity.domain.PlatformOperatorStatus;
import com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence.PlatformOperatorEntity;
import com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence.PlatformOperatorRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Resolves a platform JWT against current operator state on every authenticated request.
 * Cryptographic validation is performed by the dedicated platform JwtDecoder first.
 */
@Component
@Profile("wms")
@ConditionalOnProperty(prefix = "wms.platform.security.jwt", name = "enabled", havingValue = "true")
public class PlatformJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private static final Duration MAX_MFA_AGE_AT_REQUEST = Duration.ofMinutes(15);

    private final PlatformOperatorRepository operatorRepository;
    private final Clock clock;

    public PlatformJwtAuthenticationConverter(PlatformOperatorRepository operatorRepository) {
        this(operatorRepository, Clock.systemUTC());
    }

    PlatformJwtAuthenticationConverter(PlatformOperatorRepository operatorRepository, Clock clock) {
        this.operatorRepository = operatorRepository;
        this.clock = clock;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Object subject = jwt.getClaims().get("sub");
        Object credentialVersionClaim = jwt.getClaims().get("cv");
        Object mfaClaim = jwt.getClaims().get("mfa");
        Object mfaAtClaim = jwt.getClaims().get("mfa_at");
        Instant issuedAt = jwt.getIssuedAt();

        if (!(subject instanceof String operatorPublicId) || operatorPublicId.isBlank()
                || !(credentialVersionClaim instanceof Number credentialVersion)
                || credentialVersion.longValue() < 0
                || credentialVersion.doubleValue() != credentialVersion.longValue()
                || !Boolean.TRUE.equals(mfaClaim)
                || !(mfaAtClaim instanceof Number mfaAtEpochSeconds)
                || issuedAt == null
                || Instant.ofEpochSecond(mfaAtEpochSeconds.longValue()).isAfter(issuedAt)
                || Instant.ofEpochSecond(mfaAtEpochSeconds.longValue())
                        .isBefore(clock.instant().minus(MAX_MFA_AGE_AT_REQUEST))) {
            throw invalidToken();
        }

        PlatformOperatorEntity operator = operatorRepository.findByPublicId(operatorPublicId)
                .filter(candidate -> candidate.getStatus() == PlatformOperatorStatus.ACTIVE)
                .filter(candidate -> candidate.getEmailVerifiedAt() != null)
                .filter(candidate -> candidate.getCredentialVersion() != null
                        && candidate.getCredentialVersion().longValue() == credentialVersion.longValue())
                .orElseThrow(PlatformJwtAuthenticationConverter::invalidToken);

        PlatformAuthenticatedPrincipal principal = new PlatformAuthenticatedPrincipal(operator.getPublicId(), Instant.ofEpochSecond(mfaAtEpochSeconds.longValue()));
        // No platform permissions are granted by this slice. Permission loading is a later, explicit step.
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    private static OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token", "Invalid bearer token", null));
    }
}
