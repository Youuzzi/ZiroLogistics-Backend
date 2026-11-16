package com.zirocraft.zirologistics.wms.security.authentication.application;

import com.zirocraft.zirologistics.wms.identity.application.WmsUserLookupService;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.WmsLoginMembershipProjection;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.WmsLoginMembershipRepository;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtTokenService;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsLoginThrottleDecision;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsLoginThrottleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsCredentialAuthenticationServiceTest {

    private WmsLoginThrottleService throttleService;
    private WmsUserLookupService userLookupService;
    private PasswordEncoder passwordEncoder;
    private WmsUnknownUserPasswordVerifier dummyVerifier;
    private WmsLoginMembershipRepository membershipRepository;
    private WmsJwtTokenService tokenService;
    private WmsLoginThrottleService.Attempt attempt;
    private WmsCredentialAuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        throttleService = mock(WmsLoginThrottleService.class);
        userLookupService = mock(WmsUserLookupService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        dummyVerifier = mock(WmsUnknownUserPasswordVerifier.class);
        membershipRepository = mock(WmsLoginMembershipRepository.class);
        tokenService = mock(WmsJwtTokenService.class);
        attempt = mock(WmsLoginThrottleService.Attempt.class);

        when(throttleService.beginAttempt("user@example.com", "198.51.100.10", null))
                .thenReturn(new WmsLoginThrottleDecision(
                        WmsLoginThrottleDecision.Reason.ALLOWED, 1, null, attempt));

        authenticationService = new WmsCredentialAuthenticationService(
                throttleService, userLookupService, passwordEncoder, dummyVerifier,
                membershipRepository, tokenService);
    }

    @Test
    void unknownEmailPerformsDummyVerificationAndRecordsAccountFailure() {
        when(userLookupService.findByEmail("user@example.com")).thenReturn(Optional.empty());

        assertThrows(WmsCredentialAuthenticationException.class,
                () -> authenticate("submitted-password"));

        verify(dummyVerifier).performDummyVerification("submitted-password");
        verify(throttleService).recordFailedCredential(attempt);
        verifyNoInteractions(passwordEncoder, membershipRepository, tokenService);
        verify(throttleService, never()).recordSuccessfulActiveUserAuthentication(any());
    }

    @Test
    void wrongPasswordRecordsFailureAndDoesNotIssueToken() {
        UserEntity user = activeUser();
        when(userLookupService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "stored-hash")).thenReturn(false);

        assertThrows(WmsCredentialAuthenticationException.class,
                () -> authenticate("wrong-password"));

        verify(throttleService).recordFailedCredential(attempt);
        verifyNoInteractions(dummyVerifier, membershipRepository, tokenService);
    }

    @Test
    void inactiveUserWithCorrectPasswordIsRejectedAndCountsAsFailure() {
        UserEntity user = activeUser();
        user.setStatus(UserStatus.SUSPENDED);
        when(userLookupService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "stored-hash")).thenReturn(true);

        assertThrows(WmsCredentialAuthenticationException.class,
                () -> authenticate("correct-password"));

        verify(throttleService).recordFailedCredential(attempt);
        verifyNoInteractions(dummyVerifier, membershipRepository, tokenService);
    }

    @Test
    void malformedStoredHashGetsDummyWorkAndIsNeverAccepted() {
        UserEntity user = activeUser();
        when(userLookupService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("submitted-password", "stored-hash"))
                .thenThrow(new IllegalArgumentException("unsupported hash"));

        assertThrows(WmsCredentialAuthenticationException.class,
                () -> authenticate("submitted-password"));

        verify(dummyVerifier).performDummyVerification("submitted-password");
        verify(throttleService).recordFailedCredential(attempt);
        verifyNoInteractions(membershipRepository, tokenService);
    }

    @Test
    void activeUserSuccessResetsThrottleBeforeLoadingMembershipsAndIssuingToken() {
        UserEntity user = activeUser();
        WmsLoginMembershipProjection membership = mock(WmsLoginMembershipProjection.class);
        when(userLookupService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "stored-hash")).thenReturn(true);
        when(userLookupService.recordSuccessfulLogin(17L)).thenReturn(true);
        when(membershipRepository.findSelectableMemberships(17L)).thenReturn(List.of(membership));
        when(membership.getMembershipPublicId()).thenReturn("membership-public-id");
        when(membership.getTenantPublicId()).thenReturn("tenant-public-id");
        when(membership.getTenantCode()).thenReturn("tenant-code");
        when(membership.getTenantDisplayName()).thenReturn("Tenant Display");
        when(tokenService.issueTenantSelectionToken("user-public-id")).thenReturn("selection-token");

        WmsCredentialAuthenticationResult result = authenticate("correct-password");

        assertEquals("selection-token", result.tenantSelectionToken());
        assertEquals(1, result.memberships().size());
        assertEquals("membership-public-id", result.memberships().getFirst().membershipPublicId());
        assertEquals("tenant-public-id", result.memberships().getFirst().tenantPublicId());
        assertEquals("tenant-code", result.memberships().getFirst().tenantCode());
        assertEquals("Tenant Display", result.memberships().getFirst().tenantDisplayName());

        var order = inOrder(throttleService, userLookupService, membershipRepository, tokenService);
        order.verify(throttleService).recordSuccessfulActiveUserAuthentication(attempt);
        order.verify(userLookupService).recordSuccessfulLogin(17L);
        order.verify(membershipRepository).findSelectableMemberships(17L);
        order.verify(tokenService).issueTenantSelectionToken("user-public-id");
        verify(throttleService, never()).recordFailedCredential(any());
        verifyNoInteractions(dummyVerifier);
    }

    @Test
    void failedLoginMetadataUpdatePreventsMembershipLookupAndTokenIssuance() {
        UserEntity user = activeUser();
        when(userLookupService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "stored-hash")).thenReturn(true);
        when(userLookupService.recordSuccessfulLogin(17L)).thenReturn(false);

        assertThrows(WmsCredentialAuthenticationException.class,
                () -> authenticate("correct-password"));

        verify(throttleService).recordSuccessfulActiveUserAuthentication(attempt);
        verify(userLookupService).recordSuccessfulLogin(17L);
        verifyNoInteractions(membershipRepository, tokenService);
    }

    @Test
    void failedThrottleResetPreventsMembershipLookupAndTokenIssuance() {
        UserEntity user = activeUser();
        when(userLookupService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "stored-hash")).thenReturn(true);
        when(throttleService.recordSuccessfulActiveUserAuthentication(attempt))
                .thenThrow(new RuntimeException("ambiguous commit"));

        assertThrows(RuntimeException.class, () -> authenticate("correct-password"));

        verifyNoInteractions(membershipRepository, tokenService);
    }

    @Test
    void throttleRejectionStopsBeforeUserLookupOrPasswordVerification() {
        when(throttleService.beginAttempt("user@example.com", "198.51.100.10", null))
                .thenReturn(new WmsLoginThrottleDecision(
                        WmsLoginThrottleDecision.Reason.SOURCE_LIMIT_EXCEEDED, 11, null, null));

        assertThrows(WmsCredentialAuthenticationException.class,
                () -> authenticate("submitted-password"));

        verifyNoInteractions(userLookupService, passwordEncoder, dummyVerifier,
                membershipRepository, tokenService);
    }

    private WmsCredentialAuthenticationResult authenticate(String password) {
        return authenticationService.authenticate(
                "user@example.com", password, "198.51.100.10", null);
    }

    private static UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(17L);
        user.setPublicId("user-public-id");
        user.setPasswordHash("stored-hash");
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}
