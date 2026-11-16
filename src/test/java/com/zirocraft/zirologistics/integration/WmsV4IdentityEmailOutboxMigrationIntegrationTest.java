package com.zirocraft.zirologistics.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class WmsV4IdentityEmailOutboxMigrationIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 12, 0);

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v4_email_outbox_test")
            .withUsername("wms_v4_test")
            .withPassword("wms_v4_test");

    @Test
    void migratesV4AfterV1ToV3AndEnforcesEncryptedOutboxContract() throws Exception {
        Flyway throughV3 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("3"))
                .load();
        throughV3.migrate();
        throughV3.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
                    "AND table_name <> 'flyway_schema_history'",
                    65, "V1+V2+V3 must contain 65 WMS tables before V4");
            assertCount(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                    "WHERE version IN ('1','2','3') AND success = 1",
                    3, "V1, V2, and V3 must be successfully applied before V4");
        }

        Flyway throughV4 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .load();
        throughV4.migrate();
        throughV4.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
                    "AND table_name <> 'flyway_schema_history'",
                    66, "V4 must add exactly one outbox table");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.referential_constraints " +
                    "WHERE constraint_schema = DATABASE()",
                    168, "V4 must not change existing foreign keys");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                    "WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK'",
                    144, "V4 must add exactly six outbox CHECK constraints");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = 'wms_identity_action_email_outbox'",
                    15, "Outbox table must expose the reviewed 15-column contract");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = 'wms_identity_action_email_outbox'",
                    8, "Outbox table must expose the primary, unique, and two explicit indexes");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = 'wms_identity_action_email_outbox' " +
                    "AND engine = 'InnoDB' AND table_collation = 'utf8mb4_0900_ai_ci'",
                    1, "Outbox table must use InnoDB and the established WMS collation");
            assertCount(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                    "WHERE version IN ('1','2','3','4') AND success = 1",
                    4, "Flyway must record V1 through V4 as successful");

            insertOutbox(connection, uuidBytes(UUID.randomUUID()), "INVITATION", "invite@example.test",
                    ciphertext((byte) 1), "PENDING", 0, null, null);
            insertOutbox(connection, uuidBytes(UUID.randomUUID()), "EMAIL_VERIFICATION", "verify@example.test",
                    ciphertext((byte) 2), "PROCESSING", 1, NOW, null);
            insertOutbox(connection, uuidBytes(UUID.randomUUID()), "PASSWORD_RESET", "reset@example.test",
                    ciphertext((byte) 3), "SENT", 1, null, NOW);
            insertOutbox(connection, uuidBytes(UUID.randomUUID()), "PASSWORD_RESET", "dead@example.test",
                    ciphertext((byte) 4), "DEAD_LETTER", 5, null, null);

            assertCount(statement,
                    "SELECT COUNT(*) FROM wms_identity_action_email_outbox",
                    4, "All four valid outbox states and all message types must be accepted");

            assertThrows(SQLException.class,
                    () -> insertOutbox(connection, uuidBytes(UUID.randomUUID()), "UNKNOWN",
                            "unknown@example.test", ciphertext((byte) 5), "PENDING", 0, null, null),
                    "Unknown message types must be rejected");
            assertThrows(SQLException.class,
                    () -> insertOutbox(connection, uuidBytes(UUID.randomUUID()), "INVITATION",
                            "invalid-status@example.test", ciphertext((byte) 6), "UNKNOWN", 0, null, null),
                    "Unknown outbox states must be rejected");
            assertThrows(SQLException.class,
                    () -> insertOutbox(connection, uuidBytes(UUID.randomUUID()), "INVITATION",
                            "invalid-processing@example.test", ciphertext((byte) 7), "PROCESSING", 0, null, null),
                    "PROCESSING rows must have a lock timestamp");
            assertThrows(SQLException.class,
                    () -> insertOutbox(connection, uuidBytes(UUID.randomUUID()), "INVITATION",
                            "invalid-sent@example.test", ciphertext((byte) 8), "SENT", 1, null, null),
                    "SENT rows must have a sent timestamp");
            assertThrows(SQLException.class,
                    () -> insertOutbox(connection, uuidBytes(UUID.randomUUID()), "INVITATION",
                            "invalid-attempt@example.test", ciphertext((byte) 9), "PENDING", -1, null, null),
                    "Attempt count cannot be negative");
            assertThrows(SQLException.class,
                    () -> insertOutbox(connection, uuidBytes(UUID.randomUUID()), "INVITATION",
                            "invalid-key@example.test", ciphertext((byte) 10), "PENDING", 0, null, null, 0),
                    "Encryption key version must be positive");
            assertThrows(SQLException.class,
                    () -> insertOutbox(connection, uuidBytes(UUID.randomUUID()), "INVITATION",
                            "short-ciphertext@example.test", new byte[16], "PENDING", 0, null, null),
                    "Encrypted payload must include ciphertext and authentication tag");
            byte[] duplicateMessageId = uuidBytes(UUID.randomUUID());
            insertOutbox(connection, duplicateMessageId, "INVITATION", "duplicate@example.test",
                    ciphertext((byte) 11), "PENDING", 0, null, null);
            assertThrows(SQLException.class,
                    () -> insertOutbox(connection, duplicateMessageId, "INVITATION",
                            "duplicate@example.test", ciphertext((byte) 12), "PENDING", 0, null, null),
                    "Message ID must be unique");
        }
    }

    private static void insertOutbox(
            Connection connection,
            byte[] messageId,
            String messageType,
            String recipient,
            byte[] ciphertext,
            String status,
            int attemptCount,
            LocalDateTime lockedAt,
            LocalDateTime sentAt
    ) throws SQLException {
        insertOutbox(connection, messageId, messageType, recipient, ciphertext,
                status, attemptCount, lockedAt, sentAt, 1);
    }

    private static void insertOutbox(
            Connection connection,
            byte[] messageId,
            String messageType,
            String recipient,
            byte[] ciphertext,
            String status,
            int attemptCount,
            LocalDateTime lockedAt,
            LocalDateTime sentAt,
            int keyVersion
    ) throws SQLException {
        String sql = """
                INSERT INTO wms_identity_action_email_outbox
                    (message_id, message_type, recipient_email, payload_ciphertext, payload_nonce,
                     encryption_key_version, status, attempt_count, available_at, locked_at, sent_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, messageId);
            statement.setString(2, messageType);
            statement.setString(3, recipient);
            statement.setBytes(4, ciphertext);
            statement.setBytes(5, new byte[12]);
            statement.setInt(6, keyVersion);
            statement.setString(7, status);
            statement.setInt(8, attemptCount);
            statement.setObject(9, NOW);
            statement.setObject(10, lockedAt);
            statement.setObject(11, sentAt);
            statement.executeUpdate();
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static byte[] ciphertext(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private static void assertCount(Statement statement, String sql, int expected, String message)
            throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), message);
            assertEquals(expected, result.getInt(1), message);
        }
    }
}
