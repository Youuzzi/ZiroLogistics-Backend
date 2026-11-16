package com.zirocraft.zirologistics.wms.identity.application;

/**
 * Generic email-verification request result that does not disclose account state.
 */
public record EmailVerificationRequestResult(boolean accepted) {
    public static EmailVerificationRequestResult acceptedRequest() {
        return new EmailVerificationRequestResult(true);
    }
}
