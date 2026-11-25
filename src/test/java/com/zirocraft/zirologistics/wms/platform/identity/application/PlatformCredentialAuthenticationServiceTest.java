package com.zirocraft.zirologistics.wms.platform.identity.application;

import com.zirocraft.zirologistics.wms.platform.identity.domain.PlatformOperatorStatus;
import com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence.PlatformOperatorEntity;
import com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence.PlatformOperatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformCredentialAuthenticationServiceTest {
    @Mock PlatformOperatorRepository repository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock PasswordEncoder dummyPasswordEncoder;
    @Mock PlatformLoginThrottlePort throttle;

    private PlatformCredentialAuthenticationService service;
    private PlatformLoginThrottlePort.Attempt attempt;
    private PlatformUnknownPasswordVerifier dummyVerifier;

    @BeforeEach
    void setUp() {
        attempt = new PlatformLoginThrottlePort.Attempt() {};
        when(throttle.beginAttempt(any(), any())).thenReturn(attempt);
        dummyVerifier = new PlatformUnknownPasswordVerifier(dummyPasswordEncoder);
        service = new PlatformCredentialAuthenticationService(
                repository, passwordEncoder, dummyVerifier, throttle);
    }

    @Test
    void authenticatesOnlyActiveVerifiedOperatorAndReturnsPlatformIdentity() {
        PlatformOperatorEntity operator = activeOperator();
        when(repository.findByEmail("ops@example.com")).thenReturn(Optional.of(operator));
        when(passwordEncoder.matches("correct-password", "argon-hash")).thenReturn(true);
        when(repository.recordSuccessfulAuthentication(41L, 3L)).thenReturn(1);

        PlatformCredentialAuthenticationResult result =
                service.authenticate(" OPS@Example.com ", "correct-password", "192.0.2.10");

        assertEquals("operator-public-id", result.operatorPublicId());
        assertEquals(3L, result.credentialVersion());
        verify(throttle).beginAttempt("ops@example.com", "192.0.2.10");
        verify(repository).recordSuccessfulAuthentication(41L, 3L);
        verify(throttle).recordSuccessfulCredential(attempt);
        verify(throttle, never()).recordFailedCredential(any());
    }

    @Test
    void unknownEmailPerformsDummyVerificationAndReturnsGenericFailure() {
        when(repository.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        when(dummyPasswordEncoder.matches("some-password", "dummy-hash")).thenReturn(true);

        assertThrows(PlatformCredentialAuthenticationException.class,
                () -> service.authenticate("missing@example.com", "some-password", "192.0.2.11"));

        verify(dummyPasswordEncoder).matches("some-password", "dummy-hash");
        verify(throttle).recordFailedCredential(attempt);
        verify(throttle, never()).recordSuccessfulCredential(any());
        verify(repository, never()).recordSuccessfulAuthentication(any(), any());
    }

    @Test
    void rejectsInactiveOrUnverifiedOperatorsEvenWhenPasswordMatches() {
        PlatformOperatorEntity operator = activeOperator();
        operator.setStatus(PlatformOperatorStatus.SUSPENDED);
        when(repository.findByEmail("ops@example.com")).thenReturn(Optional.of(operator));
        when(passwordEncoder.matches("correct-password", "argon-hash")).thenReturn(true);

        assertThrows(PlatformCredentialAuthenticationException.class,
                () -> service.authenticate("ops@example.com", "correct-password", "192.0.2.12"));

        verify(throttle).recordFailedCredential(attempt);
        verify(repository, never()).recordSuccessfulAuthentication(any(), any());
    }

    @Test
    void rejectsCredentialVersionRaceBeforeAuthenticationCanSucceed() {
        PlatformOperatorEntity operator = activeOperator();
        when(repository.findByEmail("ops@example.com")).thenReturn(Optional.of(operator));
        when(passwordEncoder.matches("correct-password", "argon-hash")).thenReturn(true);
        when(repository.recordSuccessfulAuthentication(41L, 3L)).thenReturn(0);

        assertThrows(PlatformCredentialAuthenticationException.class,
                () -> service.authenticate("ops@example.com", "correct-password", "192.0.2.13"));

        verify(throttle).recordFailedCredential(attempt);
        verify(throttle, never()).recordSuccessfulCredential(any());
    }

    private static PlatformOperatorEntity activeOperator() {
        PlatformOperatorEntity operator = new PlatformOperatorEntity();
        operator.setId(41L);
        operator.setPublicId("operator-public-id");
        operator.setEmail("ops@example.com");
        operator.setPasswordHash("argon-hash");
        operator.setFullName("Platform Operator");
        operator.setStatus(PlatformOperatorStatus.ACTIVE);
        operator.setCredentialVersion(3L);
        operator.setEmailVerifiedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return operator;
    }
}
