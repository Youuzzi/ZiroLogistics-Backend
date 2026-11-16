package com.zirocraft.zirologistics.wms.security.authentication.application;

import com.zirocraft.zirologistics.wms.identity.application.WmsUserLookupService;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.WmsLoginMembershipProjection;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.WmsLoginMembershipRepository;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtTokenService;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsLoginThrottleDecision;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsLoginThrottleService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal credential-authentication use case.
 *
 * This class intentionally has no HTTP, servlet, or Spring component wiring.
 * Deployment configuration must explicitly compose it with a reviewed
 * throttle policy, HMAC key, trusted-proxy list, and WMS-only dependencies.
 */
public final class WmsCredentialAuthenticationService {

    private final WmsLoginThrottleService throttleService;
    private final WmsUserLookupService userLookupService;
    private final PasswordEncoder passwordEncoder;
    private final WmsUnknownUserPasswordVerifier unknownUserPasswordVerifier;
    private final WmsLoginMembershipRepository membershipRepository;
    private final WmsJwtTokenService tokenService;

    public WmsCredentialAuthenticationService(
            WmsLoginThrottleService throttleService,
            WmsUserLookupService userLookupService,
            PasswordEncoder passwordEncoder,
            WmsUnknownUserPasswordVerifier unknownUserPasswordVerifier,
            WmsLoginMembershipRepository membershipRepository,
            WmsJwtTokenService tokenService) {
        this.throttleService = Objects.requireNonNull(throttleService, "throttleService must not be null");
        this.userLookupService = Objects.requireNonNull(userLookupService, "userLookupService must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.unknownUserPasswordVerifier = Objects.requireNonNull(
                unknownUserPasswordVerifier, "unknownUserPasswordVerifier must not be null");
        this.membershipRepository = Objects.requireNonNull(
                membershipRepository, "membershipRepository must not be null");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
    }

    /**
     * Authenticates a global user and returns active memberships for explicit
     * tenant selection. The caller must supply the actual socket peer address;
     * forwarded headers are interpreted only by the trusted-proxy resolver.
     */
    public WmsCredentialAuthenticationResult authenticate(
            String email,
            String password,
            String socketPeerAddress,
            String forwardedForHeader) {
        Objects.requireNonNull(password, "password must not be null");

        WmsLoginThrottleDecision throttleDecision = throttleService.beginAttempt(
                email, socketPeerAddress, forwardedForHeader);
        if (!throttleDecision.allowed()) {
            throw new WmsCredentialAuthenticationException();
        }

        WmsLoginThrottleService.Attempt attempt = throttleDecision.attempt();
        Optional<UserEntity> userOptional = userLookupService.findByEmail(email);
        if (userOptional.isEmpty()) {
            unknownUserPasswordVerifier.performDummyVerification(password);
            throttleService.recordFailedCredential(attempt);
            throw new WmsCredentialAuthenticationException();
        }

        UserEntity user = userOptional.get();
        boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(password, user.getPasswordHash());
        } catch (RuntimeException malformedOrUnsupportedHash) {
            // Preserve password-work shape without accepting an unrecognized hash format.
            unknownUserPasswordVerifier.performDummyVerification(password);
            passwordMatches = false;
        }

        if (!passwordMatches || user.getStatus() != UserStatus.ACTIVE) {
            throttleService.recordFailedCredential(attempt);
            throw new WmsCredentialAuthenticationException();
        }

        // The reset must commit before any tenant-selection token is issued.
        throttleService.recordSuccessfulActiveUserAuthentication(attempt);
        if (!userLookupService.recordSuccessfulLogin(user.getId())) {
            throw new WmsCredentialAuthenticationException();
        }

        List<WmsCredentialAuthenticationResult.SelectableMembership> memberships =
                membershipRepository.findSelectableMemberships(user.getId()).stream()
                        .map(WmsCredentialAuthenticationService::toSelectableMembership)
                        .toList();

        String selectionToken = tokenService.issueTenantSelectionToken(user.getPublicId());
        return new WmsCredentialAuthenticationResult(selectionToken, memberships);
    }

    private static WmsCredentialAuthenticationResult.SelectableMembership toSelectableMembership(
            WmsLoginMembershipProjection projection) {
        return new WmsCredentialAuthenticationResult.SelectableMembership(
                projection.getMembershipPublicId(),
                projection.getTenantPublicId(),
                projection.getTenantCode(),
                projection.getTenantDisplayName());
    }
}
