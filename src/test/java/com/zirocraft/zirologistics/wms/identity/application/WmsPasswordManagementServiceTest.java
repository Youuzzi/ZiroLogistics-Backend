package com.zirocraft.zirologistics.wms.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEncryptedEmailPayload;
import com.zirocraft.zirologistics.wms.identity.domain.WmsGeneratedIdentityActionToken;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenRecord;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WmsPasswordManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T15:00:00Z");
    private static final String EMAIL = "account@example.test";

    @Mock private UserRepository userRepository;
    @Mock private WmsUserLookupService userLookupService;
    @Mock private WmsIdentityActionTokenStoragePort tokenStorage;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ObjectProvider<WmsEmailOutboxEncryptionService> encryptionProvider;
    @Mock private WmsEmailOutboxStoragePort outboxStorage;
    @Mock private PlatformTransactionManager transactionManager;

    private final WmsIdentityActionTokenGenerator tokenGenerator = new WmsIdentityActionTokenGenerator();
    private WmsEmailOutboxEncryptionService encryptionService;
    private WmsPasswordManagementService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        lenient().doNothing().when(transactionManager).commit(any(TransactionStatus.class));
        lenient().doNothing().when(transactionManager).rollback(any(TransactionStatus.class));
        encryptionService = new WmsEmailOutboxEncryptionService(Map.of(1, new byte[32]), 1);
        service = new WmsPasswordManagementService(
                userRepository,
                userLookupService,
                tokenStorage,
                tokenGenerator,
                passwordEncoder,
                encryptionProvider,
                outboxStorage,
                new ObjectMapper().findAndRegisterModules(),
                new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void changesPasswordOnlyWhenCurrentHashSnapshotIsStillCurrent() {
        UserEntity snapshot = activeUser(42L, "account-public-42", "old-argon2-hash", 7L);
        when(userRepository.findByPublicId("account-public-42")).thenReturn(Optional.of(snapshot));
        when(passwordEncoder.matches("CurrentPassword123", "old-argon2-hash")).thenReturn(true);
        when(passwordEncoder.matches("NewPassword123", "old-argon2-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewPassword123")).thenReturn("new-argon2-hash");
        when(userRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(snapshot));
        when(userRepository.saveAndFlush(snapshot)).thenReturn(snapshot);
        when(tokenStorage.revokeOutstandingForUser(
                42L, WmsIdentityActionTokenPurpose.PASSWORD_RESET, NOW)).thenReturn(0);

        service.changePassword("account-public-42", "CurrentPassword123", "NewPassword123");

        assertEquals("new-argon2-hash", snapshot.getPasswordHash());
        verify(tokenStorage).revokeOutstandingForUser(
                42L, WmsIdentityActionTokenPurpose.PASSWORD_RESET, NOW);
        InOrder order = inOrder(passwordEncoder, transactionManager);
        order.verify(passwordEncoder).encode("NewPassword123");
        order.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
    }

    @Test
    void rejectsIncorrectCurrentPasswordBeforeOpeningWriteTransaction() {
        UserEntity snapshot = activeUser(42L, "account-public-42", "old-argon2-hash", 7L);
        when(userRepository.findByPublicId("account-public-42")).thenReturn(Optional.of(snapshot));
        when(passwordEncoder.matches("WrongPassword123", "old-argon2-hash")).thenReturn(false);

        assertThrows(BadCredentialsException.class,
                () -> service.changePassword(
                        "account-public-42", "WrongPassword123", "NewPassword123"));

        verify(transactionManager, never()).getTransaction(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void passwordResetRequestDoesNotDiscloseUnknownEmail() {
        when(encryptionProvider.getIfAvailable()).thenReturn(encryptionService);
        when(userLookupService.findByEmail(EMAIL)).thenReturn(Optional.empty());

        PasswordResetRequestResult result = service.requestPasswordReset(" Account@Example.Test ");

        assertTrue(result.accepted());
        verifyNoInteractions(tokenStorage, outboxStorage, transactionManager);
    }

    @Test
    void passwordResetRequestStoresDigestAndEncryptedPayloadForActiveUser() throws Exception {
        when(encryptionProvider.getIfAvailable()).thenReturn(encryptionService);
        UserEntity candidate = activeUser(42L, "account-public-42", "old-argon2-hash", 7L);
        when(userLookupService.findByEmail(EMAIL)).thenReturn(Optional.of(candidate));
        when(userRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(candidate));
        when(tokenStorage.revokeOutstandingForUser(
                42L, WmsIdentityActionTokenPurpose.PASSWORD_RESET, NOW)).thenReturn(0);

        PasswordResetRequestResult result = service.requestPasswordReset(EMAIL);

        assertTrue(result.accepted());
        ArgumentCaptor<byte[]> digest = ArgumentCaptor.forClass(byte[].class);
        verify(tokenStorage).insert(digest.capture(), eq(WmsIdentityActionTokenPurpose.PASSWORD_RESET),
                eq(42L), isNull(), isNull(), eq(EMAIL),
                eq(NOW.plus(Duration.ofMinutes(30))), eq(NOW));
        assertEquals(32, digest.getValue().length);

        ArgumentCaptor<UUID> messageId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<byte[]> ciphertext = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> nonce = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Integer> keyVersion = ArgumentCaptor.forClass(Integer.class);
        verify(outboxStorage).enqueue(
                messageId.capture(),
                eq(WmsIdentityActionTokenPurpose.PASSWORD_RESET),
                eq(EMAIL),
                ciphertext.capture(),
                nonce.capture(),
                keyVersion.capture(),
                eq(NOW));

        byte[] plaintext = encryptionService.decrypt(
                messageId.getValue(),
                WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                EMAIL,
                new WmsEncryptedEmailPayload(keyVersion.getValue(), nonce.getValue(), ciphertext.getValue()));
        JsonNode payload = new ObjectMapper().findAndRegisterModules().readTree(plaintext);
        assertEquals("PASSWORD_RESET", payload.get("purpose").asText());
        assertEquals(EMAIL, payload.get("targetEmail").asText());
        assertEquals(43, payload.get("actionToken").asText().length());
    }

    @Test
    void completesPasswordResetAndRevokesOtherOutstandingResetTokens() {
        WmsGeneratedIdentityActionToken generated = tokenGenerator.generate();
        WmsIdentityActionTokenRecord token = new WmsIdentityActionTokenRecord(
                101L, generated.digest(), WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                42L, null, null, EMAIL, NOW.plusSeconds(1_800), null, null, NOW.minusSeconds(60));
        when(tokenStorage.findByDigest(generated.digest(), WmsIdentityActionTokenPurpose.PASSWORD_RESET))
                .thenReturn(Optional.of(token));
        UserEntity user = activeUser(42L, "account-public-42", "old-argon2-hash", 7L);
        when(userRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        when(tokenStorage.findByDigestForUpdate(generated.digest(), WmsIdentityActionTokenPurpose.PASSWORD_RESET))
                .thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NewPassword123")).thenReturn("new-argon2-hash");
        when(userRepository.saveAndFlush(user)).thenReturn(user);
        when(tokenStorage.markConsumed(101L, NOW)).thenReturn(1);
        when(tokenStorage.revokeOutstandingForUser(
                42L, WmsIdentityActionTokenPurpose.PASSWORD_RESET, NOW)).thenReturn(2);

        service.completePasswordReset(generated.rawToken(), "NewPassword123");

        assertEquals("new-argon2-hash", user.getPasswordHash());
        verify(tokenStorage).markConsumed(101L, NOW);
        verify(tokenStorage).revokeOutstandingForUser(
                42L, WmsIdentityActionTokenPurpose.PASSWORD_RESET, NOW);
    }

    @Test
    void rejectsExpiredPasswordResetBeforeOpeningWriteTransaction() {
        WmsGeneratedIdentityActionToken generated = tokenGenerator.generate();
        WmsIdentityActionTokenRecord expired = new WmsIdentityActionTokenRecord(
                101L, generated.digest(), WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                42L, null, null, EMAIL, NOW.minusSeconds(1), null, null, NOW.minusSeconds(60));
        when(tokenStorage.findByDigest(generated.digest(), WmsIdentityActionTokenPurpose.PASSWORD_RESET))
                .thenReturn(Optional.of(expired));

        assertThrows(WmsIdentityActionTokenRejectedException.class,
                () -> service.completePasswordReset(generated.rawToken(), "NewPassword123"));

        verify(transactionManager, never()).getTransaction(any());
        verifyNoInteractions(userRepository);
    }

    private static UserEntity activeUser(long id, String publicId, String passwordHash, long version) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setPublicId(publicId);
        user.setEmail(EMAIL);
        user.setFullName("Account User");
        user.setPasswordHash(passwordHash);
        user.setStatus(UserStatus.ACTIVE);
        user.setVersion(version);
        return user;
    }
}
