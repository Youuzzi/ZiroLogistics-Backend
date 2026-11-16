package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

/**
 * Safe membership summary returned after global-user credential authentication.
 * Never includes internal database identifiers or permission data.
 */
public interface WmsLoginMembershipProjection {
    String getMembershipPublicId();
    String getTenantPublicId();
    String getTenantCode();
    String getTenantDisplayName();
}
