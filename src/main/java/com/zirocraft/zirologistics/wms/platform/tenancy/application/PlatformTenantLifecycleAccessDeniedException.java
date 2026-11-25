package com.zirocraft.zirologistics.wms.platform.tenancy.application;

public class PlatformTenantLifecycleAccessDeniedException extends RuntimeException {
    public PlatformTenantLifecycleAccessDeniedException() {
        super("Platform tenant lifecycle operation is not authorized");
    }
}
