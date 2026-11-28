package com.zirocraft.zirologistics.wms.security.mfa.application;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsTenantMfaReauthenticationServiceTest {

    private UserRepository userRepository;
    private WmsMembershipAccessService membershipAccessService;
    private PasswordEncoder passwordEncoder;
    private WmsTenantMfaReauthenticationGrantStoragePort grantStorage;
    private PlatformTransactionManager transactionManager;
    private WmsTenantMfaReauthenticationService service;
    private WmsAuthenticatedPrincipal principal;
    private UserEntity user;
    private WmsMembershipAccess membership;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        membershipAccessService = mock(WmsMembershipAccessService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        grantStorage = mock(WmsTenantMfaReauthenticationGrantStoragePort.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        principal = new WmsAuthenticatedPrincipal(
                "user-public-id", 29L, "tenant-public-id", 41L,
                "membership-public-id", 7L, Set.of("WMS_MFA_MANAGE"));
        user = activeUser();
        membership = new WmsMembershipAccess(
                17L, "user-public-id", 29L, "tenant-public-id",
                41L, "membership-public-id", 7L, Set.of("WMS_MFA_MANAGE"));

        when(userRepository.findByPublicId("user-public-id")).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(17L)).thenReturn(Optional.of(user));
        when(membershipAccessService.resolveForUpdate("user-public-id", "membership-public-id"))
                .thenReturn(membership);

        service = new WmsTenantMfaReauthenticationService(
                userRepository, membershipAccessService, passwordEncoder, grantStorage,
                transactionManager, Clock.fixed(Instant.parse("2026-11-28T10:00:00Z"), ZoneOffset.UTC),
                new java.security.SecureRandom());
    }

    @Test
    void issuesShortLivedDigestOnlyGrantForExactActiveMembership() {
        when(passwordEncoder.matches("correct-password", "argon2id-hash")).thenReturn(true);

        WmsTenantMfaReauthenticationGrant grant = service.issue(
                principal, "correct-password", WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT);

        assertEquals(43, grant.value().length());
        assertEquals(Instant.parse("2026-11-28T10:05:00Z"), grant.expiresAt());
        verify(grantStorage).insert(
                any(), argThat(digest -> digest.length == 32), eq(17L), eq(29L), eq(41L), eq(7L),
                eq(WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT),
                eq(Instant.parse("2026-11-28T10:00:00Z")),
                eq(Instant.parse("2026-11-28T10:05:00Z")));
    }

    @Test
    void rejectsWrongPasswordWithoutIssuingGrant() {
        when(passwordEncoder.matches("wrong-password", "argon2id-hash")).thenReturn(false);

        assertThrows(WmsTenantMfaReauthenticationRejectedException.class,
                () -> service.issue(principal, "wrong-password",
                        WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT));

        verifyNoInteractions(grantStorage);
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void rejectsStaleCredentialVersionBeforeGrantIssuance() {
        user.setCredentialVersion(8L);
        when(passwordEncoder.matches("correct-password", "argon2id-hash")).thenReturn(true);

        assertThrows(WmsTenantMfaReauthenticationRejectedException.class,
                () -> service.issue(principal, "correct-password",
                        WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT));

        verifyNoInteractions(grantStorage);
    }

    @Test
    void consumesGrantForTheExactIdentityAndPurpose() {
        when(grantStorage.consume(any(), eq(17L), eq(29L), eq(41L), eq(7L),
                eq(WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT), any()))
                .thenReturn(true);

        String validGrant = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assertDoesNotThrow(() -> service.consume(
                principal, validGrant, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT));

        verify(grantStorage).consume(
                argThat(digest -> digest.length == 32), eq(17L), eq(29L), eq(41L), eq(7L),
                eq(WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT),
                eq(Instant.parse("2026-11-28T10:00:00Z")));
    }

    @Test
    void consumptionRequiresAnExistingCallerTransaction() {
        when(grantStorage.consume(any(), eq(17L), eq(29L), eq(41L), eq(7L),
                eq(WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT), any()))
                .thenReturn(true);

        service.consume(principal, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT);

        org.mockito.ArgumentCaptor<org.springframework.transaction.TransactionDefinition> definition =
                org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_MANDATORY,
                definition.getValue().getPropagationBehavior());
    }

    @Test
    void rejectsMalformedGrantBeforeStorageAccess() {
        assertThrows(WmsTenantMfaReauthenticationRejectedException.class,
                () -> service.consume(principal, "not-a-grant",
                        WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT));
        verifyNoInteractions(grantStorage);
    }

    @Test
    void rejectsMembershipThatNoLongerMatchesAuthenticatedPrincipal() {
        when(passwordEncoder.matches("correct-password", "argon2id-hash")).thenReturn(true);
        when(membershipAccessService.resolveForUpdate("user-public-id", "membership-public-id"))
                .thenReturn(new WmsMembershipAccess(
                        17L, "user-public-id", 30L, "other-tenant-public-id",
                        41L, "membership-public-id", 7L, Set.of()));

        assertThrows(WmsTenantMfaReauthenticationRejectedException.class,
                () -> service.issue(principal, "correct-password",
                        WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT));
        verifyNoInteractions(grantStorage);
    }

    @Test
    void revokesOutstandingGrantsForSecurityChanges() {
        when(grantStorage.revokeOutstandingForUser(eq(17L), any())).thenReturn(3);

        assertEquals(3, service.revokeOutstandingForUser(17L));
        verify(grantStorage).revokeOutstandingForUser(
                17L, Instant.parse("2026-11-28T10:00:00Z"));
    }

    private static UserEntity activeUser() {
        UserEntity entity = new UserEntity();
        entity.setId(17L);
        entity.setPublicId("user-public-id");
        entity.setPasswordHash("argon2id-hash");
        entity.setStatus(UserStatus.ACTIVE);
        entity.setCredentialVersion(7L);
        entity.setVersion(3L);
        return entity;
    }
}
