package com.zirocraft.zirologistics.wms.identity.infrastructure.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxEncryptionService;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxStoragePort;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEmailOutboxMessage;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEncryptedEmailPayload;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.infrastructure.config.WmsEmailOutboxDeliveryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Delivers encrypted identity-action outbox messages over authenticated SMTP.
 * Claims one row at a time so each message receives a fresh processing lease.
 * No token, recipient, ciphertext, or provider exception is logged.
 */
@Component
@Profile("wms")
@ConditionalOnProperty(
        prefix = "wms.identity.email.delivery",
        name = "enabled",
        havingValue = "true")
public class WmsEmailOutboxDeliveryWorker {

    private final WmsEmailOutboxStoragePort storage;
    private final WmsEmailOutboxEncryptionService encryption;
    private final ObjectMapper objectMapper;
    private final JavaMailSender mailSender;
    private final WmsEmailOutboxDeliveryProperties properties;
    private final Clock clock;

    public WmsEmailOutboxDeliveryWorker(
            WmsEmailOutboxStoragePort storage,
            WmsEmailOutboxEncryptionService encryption,
            ObjectMapper objectMapper,
            JavaMailSender mailSender,
            WmsEmailOutboxDeliveryProperties properties) {
        this(storage, encryption, objectMapper, mailSender, properties, Clock.systemUTC());
    }

    WmsEmailOutboxDeliveryWorker(
            WmsEmailOutboxStoragePort storage,
            WmsEmailOutboxEncryptionService encryption,
            ObjectMapper objectMapper,
            JavaMailSender mailSender,
            WmsEmailOutboxDeliveryProperties properties,
            Clock clock) {
        this.storage = Objects.requireNonNull(storage);
        this.encryption = Objects.requireNonNull(encryption);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.mailSender = Objects.requireNonNull(mailSender);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelayString = "${wms.identity.email.delivery.poll-interval-ms:5000}")
    public void poll() {
        Instant now = clock.instant();
        storage.recoverStaleProcessing(
                now.minus(properties.getProcessingLease()),
                now,
                properties.getMaxAttempts(),
                Math.min(properties.getMaxMessagesPerPoll(), 100));

        for (int i = 0; i < properties.getMaxMessagesPerPoll(); i++) {
            List<WmsEmailOutboxMessage> claimed = storage.claimAvailable(clock.instant(), 1);
            if (claimed.isEmpty()) {
                return;
            }
            deliver(claimed.getFirst());
        }
    }

    void deliver(WmsEmailOutboxMessage message) {
        try {
            byte[] plaintext = encryption.decrypt(
                    message.messageId(),
                    message.messageType(),
                    message.recipientEmail(),
                    new WmsEncryptedEmailPayload(
                            message.encryptionKeyVersion(),
                            message.payloadNonce(),
                            message.payloadCiphertext()));
            try {
                DeliveryContent content = render(message, plaintext);
                if (!content.expiresAt().isAfter(clock.instant())) {
                    storage.scheduleRetry(message.messageId(), message.attemptCount(),
                            clock.instant(), "ACTION_TOKEN_EXPIRED", message.attemptCount());
                    return;
                }
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(properties.getFromAddress());
                mail.setTo(message.recipientEmail());
                mail.setSubject(content.subject());
                mail.setText(content.body());
                mailSender.send(mail);
                storage.markSent(message.messageId(), message.attemptCount(), clock.instant());
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        } catch (Exception deliveryFailure) {
            Duration delay = retryDelay(message.attemptCount());
            storage.scheduleRetry(
                    message.messageId(),
                    message.attemptCount(),
                    clock.instant().plus(delay),
                    "DELIVERY_FAILED",
                    properties.getMaxAttempts());
        }
    }

    private DeliveryContent render(WmsEmailOutboxMessage message, byte[] plaintext) throws Exception {
        return switch (message.messageType()) {
            case INVITATION -> {
                InvitationPayload payload = objectMapper.readValue(plaintext, InvitationPayload.class);
                requirePayload(message, payload.purpose(), payload.actionToken(), payload.targetEmail());
                yield new DeliveryContent(
                        "You have been invited to a WMS workspace",
                        "You have been invited to join a WMS workspace.\n\n"
                                + "Accept the invitation using this secure link:\n"
                                + actionUrl(properties.getInvitationPath(), payload.actionToken())
                                + "\n\nThis link expires at " + payload.expiresAt() + ".\n"
                                + "If you were not expecting this invitation, you can ignore this email.",
                        payload.expiresAt());
            }
            case EMAIL_VERIFICATION -> {
                VerificationPayload payload = objectMapper.readValue(plaintext, VerificationPayload.class);
                requirePayload(message, payload.purpose(), payload.actionToken(), payload.targetEmail());
                yield new DeliveryContent(
                        "Verify your WMS email address",
                        "Please verify this email address to complete your WMS account setup.\n\n"
                                + "Verify email:\n" + actionUrl(properties.getVerificationPath(), payload.actionToken())
                                + "\n\nThis link expires at " + payload.expiresAt() + ".\n"
                                + "If you did not request this, you can ignore this email.",
                        payload.expiresAt());
            }
            case PASSWORD_RESET -> {
                PasswordResetPayload payload = objectMapper.readValue(plaintext, PasswordResetPayload.class);
                requirePayload(message, payload.purpose(), payload.actionToken(), payload.targetEmail());
                yield new DeliveryContent(
                        "Reset your WMS password",
                        "A password reset was requested for this account.\n\n"
                                + "Reset password:\n" + actionUrl(properties.getPasswordResetPath(), payload.actionToken())
                                + "\n\nThis link expires at " + payload.expiresAt() + ".\n"
                                + "If you did not request this, you can ignore this email.",
                        payload.expiresAt());
            }
        };
    }

    private static void requirePayload(
            WmsEmailOutboxMessage message, String purpose, String actionToken, String targetEmail) {
        if (!message.messageType().name().equals(purpose)
                || actionToken == null || actionToken.isBlank() || actionToken.length() > 512
                || !message.recipientEmail().equals(targetEmail)) {
            throw new IllegalArgumentException("WMS identity email payload does not match its outbox envelope");
        }
    }

    private String actionUrl(String path, String token) {
        URI base = URI.create(properties.getActionBaseUrl());
        URI target = base.resolve(path);
        return UriComponentsBuilder.fromUri(target)
                .fragment("token=" + token)
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUriString();
    }

    private Duration retryDelay(int attemptCount) {
        long multiplier = 1L << Math.min(Math.max(attemptCount - 1, 0), 30);
        Duration base = properties.getRetryBaseDelay();
        Duration maximum = properties.getRetryMaxDelay();
        try {
            Duration calculated = base.multipliedBy(multiplier);
            return calculated.compareTo(maximum) > 0 ? maximum : calculated;
        } catch (ArithmeticException overflow) {
            return maximum;
        }
    }

    private record DeliveryContent(String subject, String body, Instant expiresAt) {}
    private record InvitationPayload(String purpose, String actionToken, Long tenantId,
                                     String targetEmail, Instant expiresAt) {}
    private record VerificationPayload(String purpose, String actionToken,
                                       String targetEmail, Instant expiresAt) {}
    private record PasswordResetPayload(String purpose, String actionToken,
                                        String targetEmail, Instant expiresAt) {}
}
