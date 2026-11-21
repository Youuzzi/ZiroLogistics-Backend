package com.zirocraft.zirologistics.wms.security.application;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.config.WmsJwtProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Issues short-lived WMS tokens from already authenticated or database-resolved identity data.
 *
 * This service does not authenticate passwords, select memberships, or expose HTTP endpoints.
 */
@Service
@Profile("wms")
@ConditionalOnProperty(prefix = "wms.security.jwt", name = "enabled", havingValue = "true")
public class WmsJwtTokenService {

    static final Duration TENANT_SELECTION_TTL = Duration.ofMinutes(5);
    static final Duration ACCESS_TTL = Duration.ofMinutes(10);

    private static final String TENANT_SELECTION_TOKEN_USE = "tenant_selection";
    private static final String ACCESS_TOKEN_USE = "access";

    private final JwtEncoder jwtEncoder;
    private final WmsJwtProperties properties;
    private final WmsMembershipAccessService membershipAccessService;

    public WmsJwtTokenService(
            JwtEncoder jwtEncoder,
            WmsJwtProperties properties,
            WmsMembershipAccessService membershipAccessService) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.membershipAccessService = membershipAccessService;
    }

    /**
     * Issues a tenant-selection token only after the caller has independently authenticated the user.
     */
    public String issueTenantSelectionToken(String authenticatedUserPublicId) {
        requireIdentifier(authenticatedUserPublicId, "authenticated user");

        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(authenticatedUserPublicId)
                .audience(List.of(properties.getTenantSelectionAudience()))
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(issuedAt.plus(TENANT_SELECTION_TTL))
                .id(UUID.randomUUID().toString())
                .claim("token_use", TENANT_SELECTION_TOKEN_USE)
                .build();

        return encode(claims);
    }

    /**
     * Issues a tenant-bound access token from a membership already resolved against current DB state.
     * Permissions are deliberately not copied into the token; they are resolved from the database.
     */
    public String issueAccessToken(String authenticatedUserPublicId, String selectedMembershipPublicId) {
        requireIdentifier(authenticatedUserPublicId, "authenticated user");
        requireIdentifier(selectedMembershipPublicId, "selected membership");

        WmsMembershipAccess access = membershipAccessService.resolve(
                authenticatedUserPublicId, selectedMembershipPublicId);

        Instant issuedAt = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(access.userPublicId())
                .audience(List.of(properties.getAccessAudience()))
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(issuedAt.plus(ACCESS_TTL))
                .id(UUID.randomUUID().toString())
                .claim("token_use", ACCESS_TOKEN_USE)
                .claim("mid", access.membershipPublicId())
                .claim("cv", access.credentialVersion())
                .build();

        return encode(claims);
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(properties.getKeyId())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    private static void requireIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " identifier is required");
        }
    }
}
