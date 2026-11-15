package com.zirocraft.zirologistics.wms.security.application;

import java.util.Set;

/**
 * Authenticated WMS identity with tenant scope resolved from persisted membership state.
 */
public record WmsAuthenticatedPrincipal(
        String userPublicId,
        Long tenantId,
        String tenantPublicId,
        Long membershipId,
        String membershipPublicId,
        Set<String> permissionCodes) {

    public WmsAuthenticatedPrincipal {
        if (userPublicId == null || userPublicId.isBlank()
                || tenantId == null || tenantPublicId == null || tenantPublicId.isBlank()
                || membershipId == null || membershipPublicId == null || membershipPublicId.isBlank()) {
            throw new IllegalArgumentException("A complete resolved WMS identity is required");
        }
        permissionCodes = Set.copyOf(permissionCodes);
    }

    public boolean hasPermission(String permissionCode) {
        return permissionCodes.contains(permissionCode);
    }
}
