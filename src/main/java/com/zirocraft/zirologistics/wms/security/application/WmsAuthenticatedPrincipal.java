package com.zirocraft.zirologistics.wms.security.application;

import java.util.Set;

/**
 * Authenticated WMS identity with tenant scope and credential version resolved
 * from the current persisted membership/user state.
 */
public record WmsAuthenticatedPrincipal(
        String userPublicId,
        Long tenantId,
        String tenantPublicId,
        Long membershipId,
        String membershipPublicId,
        long credentialVersion,
        Set<String> permissionCodes) {

    /**
     * Compatibility constructor for non-authentication unit fixtures.
     * Production authentication must use the credential-version-aware constructor.
     */
    public WmsAuthenticatedPrincipal(
            String userPublicId,
            Long tenantId,
            String tenantPublicId,
            Long membershipId,
            String membershipPublicId,
            Set<String> permissionCodes) {
        this(userPublicId, tenantId, tenantPublicId, membershipId, membershipPublicId, 0L, permissionCodes);
    }

    public WmsAuthenticatedPrincipal {
        if (userPublicId == null || userPublicId.isBlank()
                || tenantId == null || tenantPublicId == null || tenantPublicId.isBlank()
                || membershipId == null || membershipPublicId == null || membershipPublicId.isBlank()
                || credentialVersion < 0) {
            throw new IllegalArgumentException("A complete resolved WMS identity is required");
        }
        permissionCodes = Set.copyOf(permissionCodes);
    }

    public boolean hasPermission(String permissionCode) {
        return permissionCodes.contains(permissionCode);
    }
}
