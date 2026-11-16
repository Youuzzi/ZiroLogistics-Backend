package com.zirocraft.zirologistics.wms.identity.infrastructure.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxEncryptionService;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxStoragePort;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEmailOutboxMessage;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEmailOutboxStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEncryptedEmailPayload;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.infrastructure.config.WmsEmailOutboxDeliveryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsEmailOutboxDeliveryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-11-16T15:00:00Z");
    private static final String RECIPIENT = "person@example.test";
    private static final String TOKEN = "opaque-action-token";

    private WmsEmailOutboxStoragePort storage;
    private WmsEmailOutboxEncryptionService encryption;
    private ObjectMapper objectMapper;
    private JavaMailSender mailSender;
    private WmsEmailOutboxDeliveryProperties properties;
    private WmsEmailOutboxDeliveryWorker worker;

    @BeforeEach
    void setUp() {
        storage = mock(WmsEmailOutboxStoragePort.class);
        encryption = new WmsEmailOutboxEncryptionService(Map.of(1, new byte[32]), 1);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mailSender = mock(JavaMailSender.class);
        properties = new WmsEmailOutboxDeliveryProperties();
        properties.setFromAddress("noreply@example.test");
        properties.setActionBaseUrl("https://wms.example.test");
        properties.setMaxAttempts(4);
        properties.setMaxMessagesPerPoll(1);
        worker = new WmsEmailOutboxDeliveryWorker(
                storage, encryption, objectMapper, mailSender, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void decryptsAndSendsPasswordResetThenMarksTheExactAttemptSent() throws Exception {
        UUID id = UUID.randomUUID();
        WmsEmailOutboxMessage message = encryptedMessage(
                id, WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                """
                {"purpose":"PASSWORD_RESET","actionToken":"opaque-action-token","targetEmail":"person@example.test","expiresAt":"2026-11-16T15:30:00Z"}
                """);
        worker.deliver(message);

        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals(RECIPIENT, captor.getValue().getTo()[0]);
        assertEquals("Reset your WMS password", captor.getValue().getSubject());
        assertTrue(captor.getValue().getText().contains(
                "https://wms.example.test/reset-password?token=opaque-action-token"));
        verify(storage).markSent(id, 1, NOW);
        verify(storage, never()).scheduleRetry(any(), anyInt(), any(), anyString(), anyInt());
    }

    @Test
    void sendsInvitationAndVerificationWithPurposeSpecificRoutes() throws Exception {
        UUID invitationId = UUID.randomUUID();
        worker.deliver(encryptedMessage(invitationId, WmsIdentityActionTokenPurpose.INVITATION,
                """
                {"purpose":"INVITATION","actionToken":"invite-token","tenantId":17,"targetEmail":"person@example.test","expiresAt":"2026-11-16T15:30:00Z"}
                """));
        verify(mailSender).send(argThat(mail -> mail.getText().contains(
                "https://wms.example.test/accept-invitation?token=invite-token")));

        reset(mailSender, storage);
        UUID verificationId = UUID.randomUUID();
        worker.deliver(encryptedMessage(verificationId, WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION,
                """
                {"purpose":"EMAIL_VERIFICATION","actionToken":"verify-token","targetEmail":"person@example.test","expiresAt":"2026-11-16T15:30:00Z"}
                """));
        verify(mailSender).send(argThat(mail -> mail.getText().contains(
                "https://wms.example.test/verify-email?token=verify-token")));
        verify(storage).markSent(verificationId, 1, NOW);
    }

    @Test
    void smtpFailureSchedulesOpaqueRetryWithoutLeakingProviderException() throws Exception {
        UUID id = UUID.randomUUID();
        WmsEmailOutboxMessage message = encryptedMessage(
                id, WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                """
                {"purpose":"PASSWORD_RESET","actionToken":"opaque-action-token","targetEmail":"person@example.test","expiresAt":"2026-11-16T15:30:00Z"}
                """);
        doThrow(new IllegalStateException("SMTP password=secret recipient=person@example.test"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        worker.deliver(message);

        verify(storage).scheduleRetry(
                eq(id), eq(1), eq(NOW.plusSeconds(30)),
                eq("DELIVERY_FAILED"), eq(4));
        verify(storage, never()).markSent(any(), anyInt(), any());
    }

    @Test
    void mismatchedRecipientIsRejectedAndNeverSent() throws Exception {
        UUID id = UUID.randomUUID();
        WmsEmailOutboxMessage message = encryptedMessage(
                id, WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                """
                {"purpose":"PASSWORD_RESET","actionToken":"opaque-action-token","targetEmail":"attacker@example.test","expiresAt":"2026-11-16T15:30:00Z"}
                """);

        worker.deliver(message);

        verifyNoInteractions(mailSender);
        verify(storage).scheduleRetry(
                eq(id), eq(1), any(), eq("DELIVERY_FAILED"), eq(4));
    }

    @Test
    void expiredActionTokenMovesDirectlyToDeadLetter() throws Exception {
        UUID id = UUID.randomUUID();
        WmsEmailOutboxMessage message = encryptedMessage(
                id, WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                """
                {"purpose":"PASSWORD_RESET","actionToken":"opaque-action-token","targetEmail":"person@example.test","expiresAt":"2026-11-16T14:59:00Z"}
                """);

        worker.deliver(message);

        verifyNoInteractions(mailSender);
        verify(storage).scheduleRetry(
                eq(id), eq(1), eq(NOW), eq("ACTION_TOKEN_EXPIRED"), eq(1));
    }

    @Test
    void pollRecoversLeasesAndClaimsOnlyOneMessageAtATime() {
        when(storage.claimAvailable(any(), eq(1))).thenReturn(List.of(), List.of());
        worker.poll();

        verify(storage).recoverStaleProcessing(
                eq(NOW.minus(properties.getProcessingLease())),
                eq(NOW), eq(4), eq(1));
        verify(storage).claimAvailable(eq(NOW), eq(1));
    }

    private WmsEmailOutboxMessage encryptedMessage(
            UUID id, WmsIdentityActionTokenPurpose purpose, String json) throws Exception {
        byte[] plaintext = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        WmsEncryptedEmailPayload encrypted = encryption.encrypt(id, purpose, RECIPIENT, plaintext);
        java.util.Arrays.fill(plaintext, (byte) 0);
        return new WmsEmailOutboxMessage(
                id, purpose, RECIPIENT,
                encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(),
                WmsEmailOutboxStatus.PROCESSING, 1,
                NOW, NOW, null, null, NOW);
    }
}
