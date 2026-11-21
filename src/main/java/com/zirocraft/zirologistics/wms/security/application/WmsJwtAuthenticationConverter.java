package com.zirocraft.zirologistics.wms.security.application;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Converts a cryptographically validated WMS access JWT into a request identity.
 *
 * Membership and permissions are deliberately reloaded from the database for each conversion.
 */
@Component
@Profile("wms")
public class WmsJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final WmsMembershipAccessService membershipAccessService;

    public WmsJwtAuthenticationConverter(WmsMembershipAccessService membershipAccessService) {
        this.membershipAccessService = membershipAccessService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String userPublicId = jwt.getSubject();
        String membershipPublicId = jwt.getClaimAsString("mid");
        String tokenUse = jwt.getClaimAsString("token_use");
        Number tokenCredentialVersion = jwt.getClaim("cv");

        if (!"access".equals(tokenUse)
                || userPublicId == null || userPublicId.isBlank()
                || membershipPublicId == null || membershipPublicId.isBlank()) {
            throw invalidToken();
        }

        final WmsMembershipAccess access;
        try {
            access = membershipAccessService.resolve(userPublicId, membershipPublicId);
        } catch (WmsMembershipAccessDeniedException exception) {
            throw invalidToken();
        }

        if (!userPublicId.equals(access.userPublicId())
                || !membershipPublicId.equals(access.membershipPublicId())
                || tokenCredentialVersion == null
                || tokenCredentialVersion.longValue() != access.credentialVersion()) {
            throw invalidToken();
        }

        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                access.userPublicId(),
                access.tenantId(),
                access.tenantPublicId(),
                access.membershipId(),
                access.membershipPublicId(),
                access.permissionCodes());

        var authorities = access.permissionCodes().stream()
                .map(permission -> new SimpleGrantedAuthority("PERM_" + permission))
                .collect(Collectors.toUnmodifiableSet());

        return new WmsJwtAuthenticationToken(principal, authorities);
    }

    private static OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token", "Invalid bearer token", null));
    }
}
