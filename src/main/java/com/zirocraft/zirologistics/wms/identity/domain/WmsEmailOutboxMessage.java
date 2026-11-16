package com.zirocraft.zirologistics.wms.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable claimed or queued identity-email outbox message.
 * The payload fields contain ciphertext only.
 */
public final class WmsEmailOutboxMessage {

    private final UUID messageId;
    private final WmsIdentityActionTokenPurpose messageType;
    private final String recipientEmail;
    private final byte[] payloadCiphertext;
    private final byte[] payloadNonce;
    private final int encryptionKeyVersion;
    private final WmsEmailOutboxStatus status;
    private final int attemptCount;
    private final Instant availableAt;
    private final Instant lockedAt;
    private final Instant sentAt;
    private final String lastErrorCode;
    private final Instant createdAt;

    public WmsEmailOutboxMessage(
            UUID messageId,
            WmsIdentityActionTokenPurpose messageType,
            String recipientEmail,
            byte[] payloadCiphertext,
            byte[] payloadNonce,
            int encryptionKeyVersion,
            WmsEmailOutboxStatus status,
            int attemptCount,
            Instant availableAt,
            Instant lockedAt,
            Instant sentAt,
            String lastErrorCode,
            Instant createdAt) {
        this.messageId = Objects.requireNonNull(messageId, "messageId must not be null");
        this.messageType = Objects.requireNonNull(messageType, "messageType must not be null");
        this.recipientEmail = Objects.requireNonNull(recipientEmail, "recipientEmail must not be null");
        Objects.requireNonNull(payloadCiphertext, "payloadCiphertext must not be null");
        Objects.requireNonNull(payloadNonce, "payloadNonce must not be null");
        if (payloadCiphertext.length <= 16 || payloadNonce.length != 12) {
            throw new IllegalArgumentException("outbox payload must contain AES-GCM ciphertext and a 12-byte nonce");
        }
        if (encryptionKeyVersion < 1 || attemptCount < 0) {
            throw new IllegalArgumentException("outbox key version and attempt count are invalid");
        }
        this.payloadCiphertext = payloadCiphertext.clone();
        this.payloadNonce = payloadNonce.clone();
        this.encryptionKeyVersion = encryptionKeyVersion;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.attemptCount = attemptCount;
        this.availableAt = Objects.requireNonNull(availableAt, "availableAt must not be null");
        this.lockedAt = lockedAt;
        this.sentAt = sentAt;
        this.lastErrorCode = lastErrorCode;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public UUID messageId() { return messageId; }
    public WmsIdentityActionTokenPurpose messageType() { return messageType; }
    public String recipientEmail() { return recipientEmail; }
    public byte[] payloadCiphertext() { return payloadCiphertext.clone(); }
    public byte[] payloadNonce() { return payloadNonce.clone(); }
    public int encryptionKeyVersion() { return encryptionKeyVersion; }
    public WmsEmailOutboxStatus status() { return status; }
    public int attemptCount() { return attemptCount; }
    public Instant availableAt() { return availableAt; }
    public Instant lockedAt() { return lockedAt; }
    public Instant sentAt() { return sentAt; }
    public String lastErrorCode() { return lastErrorCode; }
    public Instant createdAt() { return createdAt; }
}
