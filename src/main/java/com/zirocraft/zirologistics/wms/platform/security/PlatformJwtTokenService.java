package com.zirocraft.zirologistics.wms.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Issues platform-only access tokens after password authentication and completed MFA.
 * This service is deliberately independent of tenant membership and tenant JWT services.
 */
@Service
@Profile("wms")
@ConditionalOnProperty(prefix = "wms.platform.security.jwt", name = "enabled", havingValue = "true")
public class PlatformJwtTokenService {
    static final Duration ACCESS_TTL = Duration.ofMinutes(10);
    static final Duration MAX_MFA_AGE = Duration.ofMinutes(5);

    private final JwtEncoder encoder;
    private final PlatformJwtProperties properties;
    private final Clock clock;

    public PlatformJwtTokenService(JwtEncoder platformJwtEncoder, PlatformJwtProperties properties) {
        this(platformJwtEncoder, properties, Clock.systemUTC());
    }

    PlatformJwtTokenService(JwtEncoder encoder, PlatformJwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issueAccessToken(String operatorPublicId, Long credentialVersion, Instant mfaAuthenticatedAt) {
        if (operatorPublicId == null || operatorPublicId.isBlank()) {
            throw new IllegalArgumentException("Platform operator identifier is required");
        }
        if (credentialVersion == null || credentialVersion < 0) {
            throw new IllegalArgumentException("Platform credential version is invalid");
        }
        Instant now = clock.instant();
        if (mfaAuthenticatedAt == null || mfaAuthenticatedAt.isAfter(now)
                || mfaAuthenticatedAt.isBefore(now.minus(MAX_MFA_AGE))) {
            throw new IllegalArgumentException("Recent successful platform MFA is required");
        }
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(operatorPublicId)
                .audience(List.of(properties.getAudience()))
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(ACCESS_TTL))
                .id(UUID.randomUUID().toString())
                .claim("token_use", "platform_access")
                .claim("cv", credentialVersion)
                .claim("mfa", true)
                .claim("mfa_at", mfaAuthenticatedAt)
                .build();
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.getKeyId()).build();
        return encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }
}
