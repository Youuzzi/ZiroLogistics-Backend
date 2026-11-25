package com.zirocraft.zirologistics.wms.platform.tenancy.application;

import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleAction;

/** Caller-supplied operation data; actor identity is deliberately not part of this request. */
public record PlatformTenantLifecycleRequest(
        WmsTenantLifecycleAction action,
        String tenantPublicId,
        String reason,
        String operationRef,
        String confirmation) {
}
