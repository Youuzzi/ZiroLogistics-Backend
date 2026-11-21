package com.zirocraft.zirologistics.wms.security.authentication.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.identity.application.WmsIdentityActionTokenRejectedException;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProfileConflictException;
import com.zirocraft.zirologistics.wms.warehouse.application.WmsWarehouseNotFoundException;
import com.zirocraft.zirologistics.wms.security.authentication.application.WmsCredentialAuthenticationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.zirocraft.zirologistics.wms")
@Profile("wms")
@Conditional(WmsHttpApiEnabledCondition.class)
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

    @ExceptionHandler({WmsWarehouseNotFoundException.class, WmsResourceNotFoundException.class})
    public ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("RESOURCE_NOT_FOUND", "The requested resource was not found"));
    }

    @ExceptionHandler(WmsIdentityActionTokenRejectedException.class)
    public ResponseEntity<ApiError> invalidActionToken() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_ACTION_TOKEN", "The action token is invalid or expired"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMediaType(HttpMediaTypeNotSupportedException ignored) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError("UNSUPPORTED_MEDIA_TYPE", "The request content type is not supported"));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception ignored) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "The request is invalid"));
    }

    @ExceptionHandler({IllegalStateException.class, WmsTenantProfileConflictException.class,
            DataIntegrityViolationException.class})
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
