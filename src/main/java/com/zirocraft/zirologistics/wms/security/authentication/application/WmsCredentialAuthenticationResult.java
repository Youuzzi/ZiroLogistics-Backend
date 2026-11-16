package com.zirocraft.zirologistics.wms.security.authentication.application;

import java.util.List;

/**
 * Successful global-user authentication awaiting explicit tenant selection.
 */
public record WmsCredentialAuthenticationResult(
        String tenantSelectionToken,
        List<SelectableMembership> memberships) {

    public WmsCredentialAuthenticationResult {
        if (tenantSelectionToken == null || tenantSelectionToken.isBlank()) {
            throw new IllegalArgumentException("tenantSelectionToken must not be blank");
        }
        memberships = List.copyOf(memberships);
    }

    public record SelectableMembership(
            String membershipPublicId,
            String tenantPublicId,
            String tenantCode,
            String tenantDisplayName) {
    }
}
