package com.zirocraft.zirologistics.wms.application;

/** Signals that a tenant-scoped WMS resource could not be found. */
public class WmsResourceNotFoundException extends RuntimeException {
    public WmsResourceNotFoundException(String message) {
        super(message);
    }
}
