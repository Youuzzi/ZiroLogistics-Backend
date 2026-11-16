package com.zirocraft.zirologistics.wms.security.throttling.application;

/**
 * Generic fail-closed signal when shared throttle storage cannot establish
 * a committed decision. HTTP mapping belongs to the future authentication
 * boundary and must not disclose storage details.
 */
public final class WmsThrottleUnavailableException extends RuntimeException {

    public WmsThrottleUnavailableException(Throwable cause) {
        super("Authentication is temporarily unavailable", cause);
    }
}
