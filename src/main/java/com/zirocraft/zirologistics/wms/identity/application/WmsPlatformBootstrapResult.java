package com.zirocraft.zirologistics.wms.identity.application;

/**
 * Non-sensitive confirmation of the one-time WMS tenant bootstrap.
 */
public record WmsPlatformBootstrapResult(
        String tenantPublicId,
        String adminUserPublicId,
        String membershipPublicId,
        String ownerRolePublicId) {
}
