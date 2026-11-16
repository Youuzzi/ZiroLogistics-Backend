package com.zirocraft.zirologistics.wms.tenancy.application;

import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;

public record WmsTenantProfile(
        String publicId,
        String tenantCode,
        String legalName,
        String displayName,
        TenantStatus status,
        String timezone,
        String defaultCurrency,
        long version) {
}
