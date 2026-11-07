package com.zirocraft.zirologistics.exception;

import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * Represents an expected application error with a safe client-facing message.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public HttpStatus getStatus() {
        return status;
    }
}
