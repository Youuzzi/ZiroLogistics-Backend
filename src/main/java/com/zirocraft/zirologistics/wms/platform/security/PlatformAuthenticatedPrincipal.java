package com.zirocraft.zirologistics.wms.platform.security;

import java.time.Instant;

/** Authenticated control-plane identity; intentionally contains no tenant context. */
public record PlatformAuthenticatedPrincipal(String operatorPublicId, Instant mfaAuthenticatedAt) {
    public PlatformAuthenticatedPrincipal {
        if (operatorPublicId == null || operatorPublicId.isBlank()) {
            throw new IllegalArgumentException("Platform operator public identifier is required");
        }
    }

    /** Compatibility constructor for low-risk identity use; privileged checks require an MFA timestamp. */
    public PlatformAuthenticatedPrincipal(String operatorPublicId) {
        this(operatorPublicId, null);
    }
}
