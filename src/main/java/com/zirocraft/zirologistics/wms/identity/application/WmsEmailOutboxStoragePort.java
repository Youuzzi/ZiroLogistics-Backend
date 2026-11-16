package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.identity.domain.WmsEmailOutboxMessage;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application-owned persistence port for encrypted identity-email delivery.
 * No method accepts or returns plaintext email payloads or bearer tokens.
 */
public interface WmsEmailOutboxStoragePort {

    void enqueue(
            UUID messageId,
            WmsIdentityActionTokenPurpose messageType,
            String normalizedRecipientEmail,
            byte[] payloadCiphertext,
            byte[] payloadNonce,
            int encryptionKeyVersion,
            Instant availableAt);

    /**
     * Claims at most limit pending messages with row locks and SKIP LOCKED.
     * Each returned attemptCount is a fencing token for subsequent state updates.
     */
    List<WmsEmailOutboxMessage> claimAvailable(Instant now, int limit);

    /**
     * Completes only the exact processing attempt represented by attemptCount.
     */
    int markSent(UUID messageId, int attemptCount, Instant sentAt);

    /**
     * Releases a failed claim for retry, or moves it to DEAD_LETTER at maxAttempts.
     * errorCode must be an opaque stable code, never an exception message.
     */
    int scheduleRetry(
            UUID messageId,
            int attemptCount,
            Instant nextAvailableAt,
            String errorCode,
            int maxAttempts);

    /**
     * Recovers expired worker leases. A stale worker is fenced by the incremented
     * attemptCount on the next claim.
     */
    int recoverStaleProcessing(Instant lockedBefore, Instant availableAt, int maxAttempts, int batchSize);
}
