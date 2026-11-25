package com.zirocraft.zirologistics.wms.platform.security;

/** Authenticated control-plane identity; intentionally contains no tenant context. */
public record PlatformAuthenticatedPrincipal(String operatorPublicId) {
    public PlatformAuthenticatedPrincipal {
        if (operatorPublicId == null || operatorPublicId.isBlank()) {
            throw new IllegalArgumentException("Platform operator public identifier is required");
        }
    }
}
