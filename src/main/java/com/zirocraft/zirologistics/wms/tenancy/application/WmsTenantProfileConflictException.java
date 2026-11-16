package com.zirocraft.zirologistics.wms.tenancy.application;

public final class WmsTenantProfileConflictException extends RuntimeException {
    public WmsTenantProfileConflictException() {
        super("Tenant profile was changed by another request; reload and retry");
    }
}
