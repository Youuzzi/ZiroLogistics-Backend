package com.zirocraft.zirologistics.wms.platform.support.application;

public class PlatformSupportGrantRejectedException extends RuntimeException {
    public PlatformSupportGrantRejectedException() {
        super("Platform support grant operation was rejected");
    }
}
