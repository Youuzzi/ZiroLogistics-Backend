package com.zirocraft.zirologistics.wms.tenancy.application;

public record WmsTenantLifecycleResult(
        String operationRef,
        String tenantPublicId,
        String tenantCode,
        String previousStatus,
        String currentStatus,
        boolean alreadyApplied) {
}
