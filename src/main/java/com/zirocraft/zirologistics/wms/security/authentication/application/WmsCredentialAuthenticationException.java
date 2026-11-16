package com.zirocraft.zirologistics.wms.security.authentication.application;

/**
 * Generic credential rejection. HTTP presentation must not distinguish
 * unknown email, wrong password, or inactive global user.
 */
public final class WmsCredentialAuthenticationException extends RuntimeException {

    public WmsCredentialAuthenticationException() {
        super("Invalid email or password");
    }
}
