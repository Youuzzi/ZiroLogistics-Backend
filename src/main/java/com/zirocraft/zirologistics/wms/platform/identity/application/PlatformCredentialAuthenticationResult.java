package com.zirocraft.zirologistics.wms.platform.identity.application;

/** Minimal authenticated control-plane identity; no tenant membership is involved. */
public record PlatformCredentialAuthenticationResult(
        String operatorPublicId,
        Long credentialVersion) {
    public PlatformCredentialAuthenticationResult {
        if (operatorPublicId == null || operatorPublicId.isBlank()) {
            throw new IllegalArgumentException("operatorPublicId must not be blank");
        }
        if (credentialVersion == null || credentialVersion < 0) {
            throw new IllegalArgumentException("credentialVersion must be non-negative");
        }
    }
}
