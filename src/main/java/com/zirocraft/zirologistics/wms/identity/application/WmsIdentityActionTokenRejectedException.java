package com.zirocraft.zirologistics.wms.identity.application;

/**
 * Generic rejection for invalid, expired, revoked, or already-used identity action tokens.
 * The message intentionally does not disclose which validation failed.
 */
public class WmsIdentityActionTokenRejectedException extends RuntimeException {

    public WmsIdentityActionTokenRejectedException() {
        super("The identity action token is invalid or can no longer be used");
    }
}
