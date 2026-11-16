package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsGeneratedIdentityActionToken;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenRecord;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WmsInvitationAcceptanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T14:30:00Z");

    @Mock private WmsIdentityActionTokenStoragePort tokenStorage;
    @Mock private WmsUserLookupService userLookupService;
    @Mock private UserRepository userRepository;
    @Mock private TenantMembershipRepository membershipRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PlatformTransactionManager transactionManager;

    private WmsIdentityActionTokenGenerator tokenGenerator;
    private WmsInvitationAcceptanceService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        doNothing().when(transactionManager).commit(any(TransactionStatus.class));
        doNothing().when(transactionManager).rollback(any(TransactionStatus.class));

        tokenGenerator = new WmsIdentityActionTokenGenerator();
        service = new WmsInvitationAcceptanceService(
                tokenStorage,
                tokenGenerator,
                userLookupService,
                userRepository,
                membershipRepository,
                passwordEncoder,
                new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsVerifiedUserAndActiveMembershipInOneTransaction() {
        WmsGeneratedIdentityActionToken generated = tokenGenerator.generate();
        WmsIdentityActionTokenRecord preview = invitationRecord(
                generated.digest(), null, "new.user@example.test", NOW.plusSeconds(3_600));
        when(tokenStorage.findByDigest(any(byte[].class), eq(WmsIdentityActionTokenPurpose.INVITATION)))
                .thenReturn(Optional.of(preview));
        when(tokenStorage.lockTenantForInvitation(5L)).thenReturn(Optional.of(TenantStatus.ACTIVE));
        when(tokenStorage.findByDigestForUpdate(any(byte[].class), eq(WmsIdentityActionTokenPurpose.INVITATION)))
                .thenReturn(Optional.of(preview));
        when(userLookupService.existsByEmail("new.user@example.test")).thenReturn(false);
        when(userRepository.findByEmail("new.user@example.test")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("StrongPassword123")).thenReturn("argon2id$test-hash");
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(81L);
            user.setPublicId("new-user-public-81");
            return user;
        });
        when(membershipRepository.findByTenantIdAndUserId(5L, 81L)).thenReturn(Optional.empty());
        when(membershipRepository.saveAndFlush(any(TenantMembershipEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(tokenStorage.markConsumed(91L, NOW)).thenReturn(1);

        WmsInvitationAcceptanceResult result = service.acceptForNewUser(
                generated.rawToken(), "  New User  ", "StrongPassword123");

        assertEquals(5L, result.tenantId());
        assertEquals("new.user@example.test", result.normalizedEmail());
        assertTrue(result.accountCreated());

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        UserEntity createdUser = userCaptor.getValue();
        assertEquals("new.user@example.test", createdUser.getEmail());
        assertEquals("New User", createdUser.getFullName());
        assertEquals("argon2id$test-hash", createdUser.getPasswordHash());
        assertEquals(UserStatus.ACTIVE, createdUser.getStatus());
        assertEquals(NOW.atOffset(ZoneOffset.UTC).toLocalDateTime(), createdUser.getEmailVerifiedAt());

        ArgumentCaptor<TenantMembershipEntity> membershipCaptor =
                ArgumentCaptor.forClass(TenantMembershipEntity.class);
        verify(membershipRepository).saveAndFlush(membershipCaptor.capture());
        TenantMembershipEntity membership = membershipCaptor.getValue();
        assertEquals(5L, membership.getTenantId());
        assertEquals(81L, membership.getUserId());
        assertEquals(MembershipStatus.ACTIVE, membership.getStatus());
        assertEquals(NOW.atOffset(ZoneOffset.UTC).toLocalDateTime(), membership.getJoinedAt());

        InOrder order = inOrder(passwordEncoder, transactionManager);
        order.verify(passwordEncoder).encode("StrongPassword123");
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        verify(tokenStorage).markConsumed(91L, NOW);
    }

    @Test
    void existingUserCanAcceptWithoutChangingCredentialsOrProfile() {
        WmsGeneratedIdentityActionToken generated = tokenGenerator.generate();
        WmsIdentityActionTokenRecord preview = invitationRecord(
                generated.digest(), 42L, "existing@example.test", NOW.plusSeconds(3_600));
        when(tokenStorage.findByDigest(any(byte[].class), eq(WmsIdentityActionTokenPurpose.INVITATION)))
                .thenReturn(Optional.of(preview));
        when(tokenStorage.lockTenantForInvitation(5L)).thenReturn(Optional.of(TenantStatus.ACTIVE));
        when(tokenStorage.findByDigestForUpdate(any(byte[].class), eq(WmsIdentityActionTokenPurpose.INVITATION)))
                .thenReturn(Optional.of(preview));

        UserEntity existingUser = new UserEntity();
        existingUser.setId(42L);
        existingUser.setPublicId("existing-user-public");
        existingUser.setEmail("EXISTING@example.test");
        existingUser.setFullName("Existing Name");
        existingUser.setPasswordHash("unchanged-argon2-hash");
        existingUser.setStatus(UserStatus.ACTIVE);
        when(userRepository.findByPublicId("existing-user-public")).thenReturn(Optional.of(existingUser));

        TenantMembershipEntity invitedMembership = new TenantMembershipEntity();
        invitedMembership.setStatus(MembershipStatus.INVITED);
        when(membershipRepository.findByTenantIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(invitedMembership));
        when(membershipRepository.saveAndFlush(invitedMembership)).thenReturn(invitedMembership);
        when(tokenStorage.markConsumed(91L, NOW)).thenReturn(1);

        WmsInvitationAcceptanceResult result =
                service.acceptForExistingUser(generated.rawToken(), "existing-user-public");

        assertFalse(result.accountCreated());
        assertEquals("existing@example.test", result.normalizedEmail());
        assertEquals("unchanged-argon2-hash", existingUser.getPasswordHash());
        assertEquals("Existing Name", existingUser.getFullName());
        assertEquals(MembershipStatus.ACTIVE, invitedMembership.getStatus());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(passwordEncoder);
        verify(tokenStorage).markConsumed(91L, NOW);
    }

    @Test
    void rejectsExpiredInvitationBeforeOpeningWriteTransaction() {
        WmsGeneratedIdentityActionToken generated = tokenGenerator.generate();
        WmsIdentityActionTokenRecord expired = invitationRecord(
                generated.digest(), null, "expired@example.test", NOW.minusSeconds(1));
        when(tokenStorage.findByDigest(any(byte[].class), eq(WmsIdentityActionTokenPurpose.INVITATION)))
                .thenReturn(Optional.of(expired));

        assertThrows(WmsIdentityActionTokenRejectedException.class,
                () -> service.acceptForNewUser(generated.rawToken(), "New User", "StrongPassword123"));

        verify(transactionManager, never()).getTransaction(any());
        verifyNoInteractions(userRepository, membershipRepository);
    }

    @Test
    void rejectsNewAccountFlowWhenEmailAlreadyHasAnAccount() {
        WmsGeneratedIdentityActionToken generated = tokenGenerator.generate();
        WmsIdentityActionTokenRecord preview = invitationRecord(
                generated.digest(), null, "existing@example.test", NOW.plusSeconds(3_600));
        when(tokenStorage.findByDigest(any(byte[].class), eq(WmsIdentityActionTokenPurpose.INVITATION)))
                .thenReturn(Optional.of(preview));
        when(userLookupService.existsByEmail("existing@example.test")).thenReturn(true);

        assertThrows(WmsIdentityActionTokenRejectedException.class,
                () -> service.acceptForNewUser(generated.rawToken(), "New User", "StrongPassword123"));

        verifyNoInteractions(passwordEncoder, transactionManager, userRepository, membershipRepository);
    }

    @Test
    void rejectsExistingUserWhoseEmailDoesNotMatchInvitation() {
        WmsGeneratedIdentityActionToken generated = tokenGenerator.generate();
        WmsIdentityActionTokenRecord preview = invitationRecord(
                generated.digest(), 42L, "expected@example.test", NOW.plusSeconds(3_600));
        when(tokenStorage.findByDigest(any(byte[].class), eq(WmsIdentityActionTokenPurpose.INVITATION)))
                .thenReturn(Optional.of(preview));
        when(tokenStorage.lockTenantForInvitation(5L)).thenReturn(Optional.of(TenantStatus.ACTIVE));
        when(tokenStorage.findByDigestForUpdate(any(byte[].class), eq(WmsIdentityActionTokenPurpose.INVITATION)))
                .thenReturn(Optional.of(preview));

        UserEntity otherUser = new UserEntity();
        otherUser.setId(42L);
        otherUser.setPublicId("other-user");
        otherUser.setEmail("other@example.test");
        otherUser.setStatus(UserStatus.ACTIVE);
        when(userRepository.findByPublicId("other-user")).thenReturn(Optional.of(otherUser));

        assertThrows(WmsIdentityActionTokenRejectedException.class,
                () -> service.acceptForExistingUser(generated.rawToken(), "other-user"));

        verify(tokenStorage, never()).markConsumed(anyLong(), any());
        verifyNoInteractions(membershipRepository);
    }

    private static WmsIdentityActionTokenRecord invitationRecord(
            byte[] digest,
            Long userId,
            String email,
            Instant expiresAt) {
        return new WmsIdentityActionTokenRecord(
                91L, digest, WmsIdentityActionTokenPurpose.INVITATION,
                userId, 5L, 7L, email, expiresAt, null, null, NOW.minusSeconds(60));
    }
}
