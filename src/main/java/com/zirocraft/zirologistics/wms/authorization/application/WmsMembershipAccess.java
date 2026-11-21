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

    /**
     * Compatibility constructor for internal fixtures representing the initial credential version.
     */
    public WmsMembershipAccess(
            Long userId,
            String userPublicId,
            Long tenantId,
            String tenantPublicId,
            Long membershipId,
            String membershipPublicId,
            Set<String> permissionCodes) {
        this(userId, userPublicId, tenantId, tenantPublicId,
                membershipId, membershipPublicId, 0L, permissionCodes);
    }

    public boolean hasPermission(String permissionCode) {
        return permissionCodes.contains(permissionCode);
    }
}
