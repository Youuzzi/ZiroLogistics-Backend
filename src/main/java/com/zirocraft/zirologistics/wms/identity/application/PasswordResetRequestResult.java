package com.zirocraft.zirologistics.wms.identity.application;

/**
 * Generic password-reset request result that does not disclose account existence.
 */
public record PasswordResetRequestResult(boolean accepted) {
    public static PasswordResetRequestResult acceptedRequest() {
        return new PasswordResetRequestResult(true);
    }
}
