package com.zirocraft.zirologistics.wms.security.authentication.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.identity.application.WmsIdentityActionTokenRejectedException;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProfileConflictException;
import com.zirocraft.zirologistics.wms.warehouse.application.WmsWarehouseNotFoundException;
import com.zirocraft.zirologistics.wms.security.authentication.application.WmsCredentialAuthenticationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = {
        "com.zirocraft.zirologistics.wms.security.authentication.http",
        "com.zirocraft.zirologistics.wms.identity.http",
        "com.zirocraft.zirologistics.wms.tenancy.http",
        "com.zirocraft.zirologistics.wms.authorization.http",
        "com.zirocraft.zirologistics.wms.warehouse.http",
        "com.zirocraft.zirologistics.wms.inventory.http"
})
@Profile("wms")
@Conditional(WmsIdentityHttpEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsAuthenticationExceptionHandler {

    @ExceptionHandler({WmsCredentialAuthenticationException.class, BadCredentialsException.class, JwtException.class})
    public ResponseEntity<ApiError> unauthorized(Exception ignored) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("AUTHENTICATION_FAILED", "Authentication failed"));
    }

    @ExceptionHandler({AccessDeniedException.class, WmsMembershipAccessDeniedException.class})
    public ResponseEntity<ApiError> forbidden(Exception ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("ACCESS_DENIED", "Access denied"));
    }

    @ExceptionHandler(WmsWarehouseNotFoundException.class)
    public ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("RESOURCE_NOT_FOUND", "The requested resource was not found"));
    }

    @ExceptionHandler(WmsIdentityActionTokenRejectedException.class)
    public ResponseEntity<ApiError> invalidActionToken() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_ACTION_TOKEN", "The action token is invalid or expired"));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "The request is invalid"));
    }

    @ExceptionHandler({IllegalStateException.class, WmsTenantProfileConflictException.class})
    public ResponseEntity<ApiError> conflict(Exception ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("REQUEST_CONFLICT", "The requested operation cannot be completed"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internalError(Exception ignored) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "The request could not be completed"));
    }

    public record ApiError(String code, String message) {}
}
