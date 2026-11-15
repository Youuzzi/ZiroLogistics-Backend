package com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence;

/**
 * Read-only projection for an active membership and its currently effective permissions.
 */
public interface WmsMembershipAccessProjection {
    Long getMembershipId();
    Long getTenantId();
    Long getUserId();
    String getMembershipPublicId();
    String getTenantPublicId();
    String getUserPublicId();
    String getPermissionCode();
}
