package com.zirocraft.zirologistics.wms.identity.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxEncryptionService;
import com.zirocraft.zirologistics.wms.identity.application.WmsInvitationAcceptanceService;
import com.zirocraft.zirologistics.wms.identity.application.WmsInvitationIssueResult;
import com.zirocraft.zirologistics.wms.identity.application.WmsTenantInvitationService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsIdentityHttpEnabledCondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/invitations")
@Conditional(WmsIdentityHttpEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsInvitationController {

    private final WmsTenantInvitationService tenantInvitationService;
    private final WmsInvitationAcceptanceService acceptanceService;
    @SuppressWarnings("unused")
    private final WmsEmailOutboxEncryptionService emailEncryptionReadiness;

    public WmsInvitationController(
            WmsTenantInvitationService tenantInvitationService,
            WmsInvitationAcceptanceService acceptanceService,
            WmsEmailOutboxEncryptionService emailEncryptionReadiness) {
        this.tenantInvitationService = tenantInvitationService;
        this.acceptanceService = acceptanceService;
        this.emailEncryptionReadiness = emailEncryptionReadiness;
    }

    @PostMapping
    public InvitationIssuedResponse invite(
            Authentication authentication,
            @Valid @RequestBody InvitationRequest request) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof WmsAuthenticatedPrincipal wmsPrincipal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        WmsInvitationIssueResult result = tenantInvitationService.invite(wmsPrincipal, request.email());
        return new InvitationIssuedResponse(true, result.expiresAt());
    }

    @PostMapping("/acceptances")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acceptForNewUser(@Valid @RequestBody NewUserAcceptanceRequest request) {
        acceptanceService.acceptForNewUser(
                request.actionToken(), request.fullName(), request.password());
    }

    @PostMapping("/existing-account-acceptances")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acceptForExistingUser(
            Authentication authentication,
            @Valid @RequestBody ExistingUserAcceptanceRequest request) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (!(principal instanceof WmsAuthenticatedPrincipal wmsPrincipal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        acceptanceService.acceptForExistingUser(request.actionToken(), wmsPrincipal.userPublicId());
    }

    public record InvitationRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record NewUserAcceptanceRequest(
            @NotBlank @Size(max = 512) String actionToken,
            @NotBlank @Size(max = 150) String fullName,
            @NotBlank @Size(max = 128) String password) {}

    public record ExistingUserAcceptanceRequest(@NotBlank @Size(max = 512) String actionToken) {}

    public record InvitationIssuedResponse(boolean accepted, Instant expiresAt) {}
}
