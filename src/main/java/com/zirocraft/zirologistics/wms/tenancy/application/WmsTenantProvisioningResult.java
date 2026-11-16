package com.zirocraft.zirologistics.wms.tenancy.application;

public record WmsTenantProvisioningResult(
        String operationRef,
        String tenantPublicId,
        String tenantCode,
        String ownerUserPublicId,
        String ownerMembershipPublicId,
        String ownerRolePublicId,
        boolean ownerVerificationRequired,
        boolean alreadyProvisioned) {
}
