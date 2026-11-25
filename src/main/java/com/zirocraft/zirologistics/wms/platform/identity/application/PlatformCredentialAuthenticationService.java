package com.zirocraft.zirologistics.wms.platform.identity.application;

import com.zirocraft.zirologistics.wms.platform.identity.domain.PlatformOperatorStatus;
import com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence.PlatformOperatorEntity;
import com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence.PlatformOperatorRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;
import java.util.Optional;

/**
 * Password-authentication use case for the SaaS control plane.
 *
 * Deliberately has no HTTP, tenant membership, tenant JWT, or automatic
 * Spring component wiring. It must be composed only with platform-specific
 * throttling and the platform token issuer in a later security slice.
 */
public final class PlatformCredentialAuthenticationService {
    private final PlatformOperatorRepository operatorRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformUnknownPasswordVerifier unknownPasswordVerifier;
    private final PlatformLoginThrottlePort throttle;

    public PlatformCredentialAuthenticationService(
            PlatformOperatorRepository operatorRepository,
            PasswordEncoder passwordEncoder,
            PlatformUnknownPasswordVerifier unknownPasswordVerifier,
            PlatformLoginThrottlePort throttle) {
        this.operatorRepository = Objects.requireNonNull(operatorRepository, "operatorRepository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.unknownPasswordVerifier = Objects.requireNonNull(
                unknownPasswordVerifier, "unknownPasswordVerifier must not be null");
        this.throttle = Objects.requireNonNull(throttle, "throttle must not be null");
    }

    /**
     * Authenticates an ACTIVE, email-verified platform operator.
     * The source address must be the canonical numeric socket peer address;
     * trusted-proxy resolution belongs at the future HTTP boundary.
     */
    public PlatformCredentialAuthenticationResult authenticate(
            String email, String password, String numericSourceAddress) {
        Objects.requireNonNull(password, "password must not be null");
        final String normalizedEmail;
        try {
            normalizedEmail = PlatformOperatorEmailNormalizer.normalize(email);
        } catch (IllegalArgumentException invalidEmail) {
            throw new PlatformCredentialAuthenticationException();
        }

        PlatformLoginThrottlePort.Attempt attempt = throttle.beginAttempt(normalizedEmail, numericSourceAddress);
        if (attempt == null) {
            throw new IllegalStateException("Platform throttle returned no attempt");
        }

        Optional<PlatformOperatorEntity> found = operatorRepository.findByEmail(normalizedEmail);
        if (found.isEmpty()) {
            unknownPasswordVerifier.performDummyVerification(password);
            throttle.recordFailedCredential(attempt);
            throw new PlatformCredentialAuthenticationException();
        }

        PlatformOperatorEntity operator = found.get();
        final boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(password, operator.getPasswordHash());
        } catch (RuntimeException malformedHash) {
            unknownPasswordVerifier.performDummyVerification(password);
            throttle.recordFailedCredential(attempt);
            throw new PlatformCredentialAuthenticationException();
        }

        if (!passwordMatches
                || operator.getStatus() != PlatformOperatorStatus.ACTIVE
                || operator.getEmailVerifiedAt() == null) {
            throttle.recordFailedCredential(attempt);
            throw new PlatformCredentialAuthenticationException();
        }

        // Conditional DB update closes the race with suspension or credential rotation.
        int updated = operatorRepository.recordSuccessfulAuthentication(
                operator.getId(), operator.getCredentialVersion());
        if (updated != 1) {
            throttle.recordFailedCredential(attempt);
            throw new PlatformCredentialAuthenticationException();
        }

        throttle.recordSuccessfulCredential(attempt);
        return new PlatformCredentialAuthenticationResult(
                operator.getPublicId(), operator.getCredentialVersion());
    }
}
