package com.zirocraft.zirologistics.wms.platform.identity.application;

/** Generic failure for platform operator credential authentication. */
public final class PlatformCredentialAuthenticationException extends RuntimeException {
    public PlatformCredentialAuthenticationException() {
        super("Platform authentication failed");
    }
}
