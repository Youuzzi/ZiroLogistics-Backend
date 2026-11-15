package com.zirocraft.zirologistics.wms.authorization.application;

/**
 * Raised when an authenticated identity cannot use the requested tenant membership.
 */
public class WmsMembershipAccessDeniedException extends RuntimeException {
    public WmsMembershipAccessDeniedException() {
        super("The requested tenant membership is not active or does not belong to this user");
    }
}
