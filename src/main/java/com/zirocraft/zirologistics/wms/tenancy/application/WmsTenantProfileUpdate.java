package com.zirocraft.zirologistics.wms.tenancy.application;

public record WmsTenantProfileUpdate(
        String legalName,
        String displayName,
        String timezone,
        String defaultCurrency,
        Long expectedVersion) {
}
