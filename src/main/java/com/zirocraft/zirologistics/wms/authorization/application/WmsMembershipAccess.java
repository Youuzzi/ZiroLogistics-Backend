package com.zirocraft.zirologistics.wms.authorization.application;

import java.util.Set;

/**
 * Server-verified tenant scope for one active user membership.
 */
public record WmsMembershipAccess(
        Long userId,
        String userPublicId,
        Long tenantId,
        String tenantPublicId,
        Long membershipId,
        String membershipPublicId,
        Long credentialVersion,
        Set<String> permissionCodes) {

    public WmsMembershipAccess {
        permissionCodes = Set.copyOf(permissionCodes);
    }

    public boolean hasPermission(String permissionCode) {
        return permissionCodes.contains(permissionCode);
    }
}
