package com.zirocraft.zirologistics.wms.security.application;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Authenticated Spring Security token carrying the server-resolved WMS principal.
 */
public final class WmsJwtAuthenticationToken extends AbstractAuthenticationToken {

    private final WmsAuthenticatedPrincipal principal;

    public WmsJwtAuthenticationToken(
            WmsAuthenticatedPrincipal principal,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public WmsAuthenticatedPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.userPublicId();
    }
}
