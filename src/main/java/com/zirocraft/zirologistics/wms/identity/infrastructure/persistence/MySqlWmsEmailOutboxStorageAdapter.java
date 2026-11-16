package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxStoragePort;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailNormalizer;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEmailOutboxMessage;
import com.zirocraft.zirologistics.wms.identity.domain.WmsEmailOutboxStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MySQL 8.4 adapter for the encrypted WMS identity-email outbox.
 * Claims use SKIP LOCKED and attemptCount fencing to prevent stale workers
 * from completing a message after its lease has been recovered.
 */
@Repository
@Profile("wms")
public class MySqlWmsEmailOutboxStorageAdapter implements WmsEmailOutboxStoragePort {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,64}");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public MySqlWmsEmailOutboxStorageAdapter(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
    }

    @Override
    public void enqueue(
            UUID messageId,
            WmsIdentityActionTokenPurpose messageType,
            String normalizedRecipientEmail,
            byte[] payloadCiphertext,
            byte[] payloadNonce,
            int encryptionKeyVersion,
            Instant availableAt) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(messageType, "messageType must not be null");
        requireCanonicalEmail(normalizedRecipientEmail);
        requireEncryptedPayload(payloadCiphertext, payloadNonce);
        if (encryptionKeyVersion < 1 || encryptionKeyVersion > 65_535) {
            throw new IllegalArgumentException("encryptionKeyVersion must be between 1 and 65535");
        }
        Objects.requireNonNull(availableAt, "availableAt must not be null");

        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO wms_identity_action_email_outbox
                        (message_id, message_type, recipient_email, payload_ciphertext, payload_nonce,
                         encryption_key_version, status, attempt_count, available_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)
                    """);
            statement.setBytes(1, uuidBytes(messageId));
            statement.setString(2, messageType.name());
            statement.setString(3, normalizedRecipientEmail);
            statement.setBytes(4, payloadCiphertext.clone());
            statement.setBytes(5, payloadNonce.clone());
            statement.setInt(6, encryptionKeyVersion);
            statement.setObject(7, utcDateTime(availableAt));
            return statement;
        });
    }

    @Override
    public List<WmsEmailOutboxMessage> claimAvailable(Instant now, int limit) {
        Objects.requireNonNull(now, "now must not be null");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("claim limit must be between 1 and 100");
        }

        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            LocalDateTime claimTime = utcDateTime(now);
            List<WmsEmailOutboxMessage> pending = jdbcTemplate.query("""
                    SELECT message_id, message_type, recipient_email, payload_ciphertext, payload_nonce,
                           encryption_key_version, status, attempt_count, available_at, locked_at,
                           sent_at, last_error_code, created_at
                    FROM wms_identity_action_email_outbox
                    WHERE status = 'PENDING'
                      AND available_at <= ?
                    ORDER BY available_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, (resultSet, rowNumber) -> mapMessage(resultSet),
                    claimTime, limit);

            List<WmsEmailOutboxMessage> claimed = new ArrayList<>(pending.size());
            for (WmsEmailOutboxMessage candidate : pending) {
                int updated = jdbcTemplate.update("""
                        UPDATE wms_identity_action_email_outbox
                        SET status = 'PROCESSING',
                            locked_at = ?,
                            attempt_count = attempt_count + 1
                        WHERE message_id = ?
                          AND status = 'PENDING'
                        """, claimTime, uuidBytes(candidate.messageId()));
                if (updated == 1) {
                    claimed.add(new WmsEmailOutboxMessage(
                            candidate.messageId(),
                            candidate.messageType(),
                            candidate.recipientEmail(),
                            candidate.payloadCiphertext(),
                            candidate.payloadNonce(),
                            candidate.encryptionKeyVersion(),
                            WmsEmailOutboxStatus.PROCESSING,
                            candidate.attemptCount() + 1,
                            candidate.availableAt(),
                            now,
                            null,
                            candidate.lastErrorCode(),
                            candidate.createdAt()));
                }
            }
            return List.copyOf(claimed);
        }), "Outbox claim transaction returned no result");
    }

    @Override
    public int markSent(UUID messageId, int attemptCount, Instant sentAt) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        requireAttemptCount(attemptCount);
        Objects.requireNonNull(sentAt, "sentAt must not be null");

        return jdbcTemplate.update("""
                UPDATE wms_identity_action_email_outbox
                SET status = 'SENT',
                    locked_at = NULL,
                    sent_at = ?,
                    last_error_code = NULL
                WHERE message_id = ?
                  AND status = 'PROCESSING'
                  AND attempt_count = ?
                """, utcDateTime(sentAt), uuidBytes(messageId), attemptCount);
    }

    @Override
    public int scheduleRetry(
            UUID messageId,
            int attemptCount,
            Instant nextAvailableAt,
            String errorCode,
            int maxAttempts) {
        Objects.requireNonNull(messageId, "messageId must not be null");
        requireAttemptCount(attemptCount);
        Objects.requireNonNull(nextAvailableAt, "nextAvailableAt must not be null");
        requireErrorCode(errorCode);
        requireMaxAttempts(maxAttempts);

        return jdbcTemplate.update("""
                UPDATE wms_identity_action_email_outbox
                SET status = CASE WHEN attempt_count >= ? THEN 'DEAD_LETTER' ELSE 'PENDING' END,
                    available_at = ?,
                    locked_at = NULL,
                    last_error_code = ?
                WHERE message_id = ?
                  AND status = 'PROCESSING'
                  AND attempt_count = ?
                """, maxAttempts, utcDateTime(nextAvailableAt), errorCode,
                uuidBytes(messageId), attemptCount);
    }

    @Override
    public int recoverStaleProcessing(Instant lockedBefore, Instant availableAt, int maxAttempts, int batchSize) {
        Objects.requireNonNull(lockedBefore, "lockedBefore must not be null");
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        requireMaxAttempts(maxAttempts);
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500");
        }

        return jdbcTemplate.update("""
                UPDATE wms_identity_action_email_outbox
                SET status = CASE WHEN attempt_count >= ? THEN 'DEAD_LETTER' ELSE 'PENDING' END,
                    available_at = ?,
                    locked_at = NULL,
                    last_error_code = 'WORKER_LEASE_EXPIRED'
                WHERE status = 'PROCESSING'
                  AND locked_at < ?
                ORDER BY locked_at, id
                LIMIT ?
                """, maxAttempts, utcDateTime(availableAt), utcDateTime(lockedBefore), batchSize);
    }

    private static WmsEmailOutboxMessage mapMessage(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new WmsEmailOutboxMessage(
                uuidFromBytes(resultSet.getBytes("message_id")),
                WmsIdentityActionTokenPurpose.valueOf(resultSet.getString("message_type")),
                resultSet.getString("recipient_email"),
                resultSet.getBytes("payload_ciphertext"),
                resultSet.getBytes("payload_nonce"),
                resultSet.getInt("encryption_key_version"),
                WmsEmailOutboxStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
                toInstant(resultSet.getObject("available_at", LocalDateTime.class)),
                toInstant(resultSet.getObject("locked_at", LocalDateTime.class)),
                toInstant(resultSet.getObject("sent_at", LocalDateTime.class)),
                resultSet.getString("last_error_code"),
                toInstant(resultSet.getObject("created_at", LocalDateTime.class)));
    }

    private static void requireCanonicalEmail(String email) {
        String normalized = WmsEmailNormalizer.normalize(email);
        if (!normalized.equals(email)) {
            throw new IllegalArgumentException("recipient email must already be normalized");
        }
    }

    private static void requireEncryptedPayload(byte[] ciphertext, byte[] nonce) {
        Objects.requireNonNull(ciphertext, "payloadCiphertext must not be null");
        Objects.requireNonNull(nonce, "payloadNonce must not be null");
        if (ciphertext.length <= 16 || ciphertext.length > 4_096 || nonce.length != 12) {
            throw new IllegalArgumentException("outbox payload must fit the AES-GCM storage contract");
        }
    }

    private static void requireAttemptCount(int attemptCount) {
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
    }

    private static void requireMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
    }

    private static void requireErrorCode(String errorCode) {
        if (errorCode == null || !ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("errorCode must be a stable uppercase code");
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID uuidFromBytes(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static LocalDateTime utcDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
