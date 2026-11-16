package com.zirocraft.zirologistics.wms.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEncryptedEmailPayload;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WmsTenantInvitationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T13:30:00Z");
    private static final WmsAuthenticatedPrincipal PRINCIPAL = new WmsAuthenticatedPrincipal(
            "user-public-1", 5L, "tenant-public-5", 11L, "membership-public-11", Set.of());

    @Mock private WmsMembershipAccessService membershipAccessService;
    @Mock private WmsIdentityActionTokenStoragePort tokenStorage;
    @Mock private WmsUserLookupService userLookupService;
    @Mock private TenantMembershipRepository membershipRepository;
    @Mock private ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider;
    @Mock private WmsEmailOutboxStoragePort outboxStorage;

    private WmsEmailOutboxEncryptionService encryptionService;
    private WmsTenantInvitationService service;

    @BeforeEach
    void setUp() {
        encryptionService = new WmsEmailOutboxEncryptionService(Map.of(1, new byte[32]), 1);
        when(encryptionProvider.getIfAvailable()).thenReturn(encryptionService);
        service = new WmsTenantInvitationService(
                membershipAccessService,
                tokenStorage,
                new WmsIdentityActionTokenGenerator(),
                userLookupService,
                membershipRepository,
                encryptionProvider,
                outboxStorage,
                new ObjectMapper().findAndRegisterModules(),
                Duration.ofHours(48),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuesDigestOnlyTokenAndQueuesEncryptedInvitationForNewIdentity() throws Exception {
        WmsMembershipAccess access = activeAccess(Set.of(WmsTenantInvitationService.INVITE_PERMISSION));
        when(membershipAccessService.resolve("user-public-1", "membership-public-11")).thenReturn(access);
        when(tokenStorage.lockTenantForInvitation(5L)).thenReturn(Optional.of(TenantStatus.ACTIVE));
        when(userLookupService.findByEmail("new.user@example.test")).thenReturn(Optional.empty());
        when(tokenStorage.insert(any(byte[].class), eq(WmsIdentityActionTokenPurpose.INVITATION),
                isNull(), eq(5L), eq(7L), eq("new.user@example.test"), eq(NOW.plus(Duration.ofHours(48))), eq(NOW)))
                .thenReturn(91L);

        WmsInvitationIssueResult result = service.invite(PRINCIPAL, " New.User@Example.Test ");

        assertEquals(91L, result.tokenId());
        assertEquals(NOW.plus(Duration.ofHours(48)), result.expiresAt());

        ArgumentCaptor<byte[]> digestCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(tokenStorage).insert(digestCaptor.capture(), eq(WmsIdentityActionTokenPurpose.INVITATION),
                isNull(), eq(5L), eq(7L), eq("new.user@example.test"),
                eq(NOW.plus(Duration.ofHours(48))), eq(NOW));
        assertEquals(32, digestCaptor.getValue().length);

        ArgumentCaptor<UUID> messageIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<byte[]> ciphertextCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> nonceCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Integer> keyVersionCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(outboxStorage).enqueue(
                messageIdCaptor.capture(),
                eq(WmsIdentityActionTokenPurpose.INVITATION),
                eq("new.user@example.test"),
                ciphertextCaptor.capture(),
                nonceCaptor.capture(),
                keyVersionCaptor.capture(),
                eq(NOW));

        WmsEncryptedEmailPayload encryptedPayload = new WmsEncryptedEmailPayload(
                keyVersionCaptor.getValue(), nonceCaptor.getValue(), ciphertextCaptor.getValue());
        byte[] plaintext = encryptionService.decrypt(
                messageIdCaptor.getValue(),
                WmsIdentityActionTokenPurpose.INVITATION,
                "new.user@example.test",
                encryptedPayload);
        JsonNode payload = new ObjectMapper().findAndRegisterModules().readTree(plaintext);
        assertEquals("INVITATION", payload.get("purpose").asText());
        assertEquals("new.user@example.test", payload.get("targetEmail").asText());
        assertEquals(5L, payload.get("tenantId").asLong());
        assertEquals(43, payload.get("actionToken").asText().length());
        verify(tokenStorage).revokeOutstandingInvitations(5L, "new.user@example.test", NOW);
    }

    @Test
    void rejectsInviterWithoutCurrentDatabasePermissionBeforeWritingAnything() {
        when(membershipAccessService.resolve("user-public-1", "membership-public-11"))
                .thenReturn(activeAccess(Set.of()));

        assertThrows(AccessDeniedException.class,
                () -> service.invite(PRINCIPAL, "new.user@example.test"));

        verifyNoInteractions(tokenStorage, userLookupService, membershipRepository, outboxStorage);
    }

    @Test
    void rejectsInvitationWhenTenantIsNotActive() {
        when(membershipAccessService.resolve("user-public-1", "membership-public-11"))
                .thenReturn(activeAccess(Set.of(WmsTenantInvitationService.INVITE_PERMISSION)));
        when(tokenStorage.lockTenantForInvitation(5L)).thenReturn(Optional.of(TenantStatus.SUSPENDED));

        assertThrows(IllegalStateException.class,
                () -> service.invite(PRINCIPAL, "new.user@example.test"));

        verify(tokenStorage, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(outboxStorage);
    }

    @Test
    void rejectsExistingMembershipAndDoesNotChangeItsStatus() {
        when(membershipAccessService.resolve("user-public-1", "membership-public-11"))
                .thenReturn(activeAccess(Set.of(WmsTenantInvitationService.INVITE_PERMISSION)));
        when(tokenStorage.lockTenantForInvitation(5L)).thenReturn(Optional.of(TenantStatus.ACTIVE));
        UserEntity existingUser = new UserEntity();
        existingUser.setId(42L);
        existingUser.setStatus(UserStatus.ACTIVE);
        when(userLookupService.findByEmail("member@example.test")).thenReturn(Optional.of(existingUser));
        TenantMembershipEntity existingMembership = new TenantMembershipEntity();
        existingMembership.setStatus(MembershipStatus.ACTIVE);
        when(membershipRepository.findByTenantIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(existingMembership));

        assertThrows(IllegalStateException.class,
                () -> service.invite(PRINCIPAL, "member@example.test"));

        verify(tokenStorage, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(outboxStorage);
    }

    private static WmsMembershipAccess activeAccess(Set<String> permissions) {
        return new WmsMembershipAccess(
                7L, "user-public-1", 5L, "tenant-public-5",
                11L, "membership-public-11", permissions);
    }
}
