package com.zirocraft.zirologistics.wms.security.authentication.http;

import com.zirocraft.zirologistics.wms.identity.application.EmailVerificationRequestResult;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxEncryptionService;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailVerificationService;
import com.zirocraft.zirologistics.wms.identity.application.WmsPasswordManagementService;
import com.zirocraft.zirologistics.wms.identity.application.PasswordResetRequestResult;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtTokenService;
import com.zirocraft.zirologistics.wms.security.authentication.application.WmsCredentialAuthenticationResult;
import com.zirocraft.zirologistics.wms.security.authentication.application.WmsCredentialAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/auth")
@Conditional(WmsIdentityHttpEnabledCondition.class)
public class WmsAuthenticationController {

    private final WmsCredentialAuthenticationService credentialAuthentication;
    private final WmsJwtTokenService jwtTokenService;
    private final JwtDecoder tenantSelectionDecoder;
    private final WmsPasswordManagementService passwordManagement;
    private final WmsEmailVerificationService emailVerification;
    @SuppressWarnings("unused")
    private final WmsEmailOutboxEncryptionService emailEncryptionReadiness;

    public WmsAuthenticationController(
            WmsCredentialAuthenticationService credentialAuthentication,
            WmsJwtTokenService jwtTokenService,
            @Qualifier("wmsTenantSelectionJwtDecoder") JwtDecoder tenantSelectionDecoder,
            WmsPasswordManagementService passwordManagement,
            WmsEmailVerificationService emailVerification,
            WmsEmailOutboxEncryptionService emailEncryptionReadiness) {
        this.credentialAuthentication = credentialAuthentication;
        this.jwtTokenService = jwtTokenService;
        this.tenantSelectionDecoder = tenantSelectionDecoder;
        this.passwordManagement = passwordManagement;
        this.emailVerification = emailVerification;
        this.emailEncryptionReadiness = emailEncryptionReadiness;
    }

    @PostMapping("/credentials")
    public CredentialLoginResponse authenticate(
            @Valid @RequestBody CredentialLoginRequest request,
            HttpServletRequest servletRequest) {
        WmsCredentialAuthenticationResult result = credentialAuthentication.authenticate(
                request.email(),
                request.password(),
                servletRequest.getRemoteAddr(),
                servletRequest.getHeader("X-Forwarded-For"));
        return new CredentialLoginResponse(result.tenantSelectionToken(), result.memberships());
    }

    @PostMapping("/tenant-selection")
    public AccessTokenResponse selectTenant(@Valid @RequestBody TenantSelectionRequest request) {
        Jwt selection = tenantSelectionDecoder.decode(request.tenantSelectionToken());
        String accessToken = jwtTokenService.issueAccessToken(selection.getSubject(), request.membershipPublicId());
        return new AccessTokenResponse(accessToken, "Bearer");
    }

    @PostMapping("/password-reset-requests")
    public AcceptedResponse requestPasswordReset(@Valid @RequestBody EmailRequest request) {
        PasswordResetRequestResult result = passwordManagement.requestPasswordReset(request.email());
        return new AcceptedResponse(result.accepted());
    }

    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completePasswordReset(@Valid @RequestBody PasswordResetCompletionRequest request) {
        passwordManagement.completePasswordReset(request.actionToken(), request.newPassword());
    }

    @PostMapping("/email-verification-requests")
    public AcceptedResponse requestEmailVerification(@Valid @RequestBody EmailRequest request) {
        EmailVerificationRequestResult result = emailVerification.requestEmailVerification(request.email());
        return new AcceptedResponse(result.accepted());
    }

    @PostMapping("/email-verifications")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeEmailVerification(@Valid @RequestBody ActionTokenRequest request) {
        emailVerification.completeEmailVerification(request.actionToken());
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

    public record CredentialLoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {}

    public record TenantSelectionRequest(
            @NotBlank @Size(max = 8192) String tenantSelectionToken,
            @NotBlank @Size(max = 36) String membershipPublicId) {}

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record PasswordResetCompletionRequest(
            @NotBlank @Size(max = 512) String actionToken,
            @NotBlank @Size(max = 128) String newPassword) {}

    public record ActionTokenRequest(@NotBlank @Size(max = 512) String actionToken) {}

    public record PasswordChangeRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 128) String newPassword) {}

    public record CredentialLoginResponse(
            String tenantSelectionToken,
            java.util.List<WmsCredentialAuthenticationResult.SelectableMembership> memberships) {}

    public record AccessTokenResponse(String accessToken, String tokenType) {}

    public record AcceptedResponse(boolean accepted) {}
}
