package com.zirocraft.zirologistics.wms.security.authentication.http;

import com.zirocraft.zirologistics.wms.identity.application.WmsPasswordManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated account password changes remain available whenever the WMS
 * JWT-protected HTTP API is enabled, independently of anonymous auth routes.
 */
@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/auth")
@Conditional(WmsHttpApiEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsPasswordChangeController {

    private final WmsPasswordManagementService passwordManagement;

    public WmsPasswordChangeController(WmsPasswordManagementService passwordManagement) {
        this.passwordManagement = passwordManagement;
    }

    @PostMapping("/password-changes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            Authentication authentication,
            @Valid @RequestBody PasswordChangeRequest request) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof WmsAuthenticatedPrincipal wmsPrincipal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        passwordManagement.changePassword(
                wmsPrincipal.userPublicId(), request.currentPassword(), request.newPassword());
    }

    public record PasswordChangeRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 128) String newPassword) {}
}
