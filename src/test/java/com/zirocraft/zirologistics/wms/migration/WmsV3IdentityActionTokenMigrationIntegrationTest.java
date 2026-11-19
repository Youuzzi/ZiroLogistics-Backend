package com.zirocraft.zirologistics.wms.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class WmsV3IdentityActionTokenMigrationIntegrationTest {

    private static final String CREATED_AT = "2026-11-16 00:00:00.000000";
    private static final String EXPIRES_AT = "2026-11-17 00:00:00.000000";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v3_identity_token_test")
            .withUsername("wms_v3_test")
            .withPassword("wms_v3_test");

    @Test
    void migratesIncrementallyFromV1AndV2AndEnforcesTokenIntegrity() throws Exception {
        Flyway throughV2 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("2"))
                .load();
        throughV2.migrate();
        throughV2.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
                    "AND table_name <> 'flyway_schema_history'",
                    64, "The pre-existing V1+V2 state must contain 64 WMS tables");
            assertCount(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1','2') AND success = 1",
                    2, "V1 and V2 must be successfully applied before V3");
        }

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
                    65, "V3 must add exactly one table to the existing V1+V2 schema");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.referential_constraints " +
                    "WHERE constraint_schema = DATABASE()",
                    168, "V3 must add exactly three restrictive foreign keys");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                    "WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK'",
                    138, "V3 must add exactly four CHECK constraints");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = 'wms_identity_action_tokens' " +
                    "AND engine = 'InnoDB' AND table_collation = 'utf8mb4_0900_ai_ci'",
                    1, "V3 table must use InnoDB and the frozen WMS collation");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = 'wms_identity_action_tokens'",
                    11, "V3 must expose the reviewed 11-column token contract");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = 'wms_identity_action_tokens'",
                    15, "V3 must create the primary, unique, and four explicit lookup/cleanup indexes");
            assertCount(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                    "WHERE version IN ('1','2','3') AND success = 1",
                    3, "Flyway must record V1, V2, and V3 as successful");

            long tenantId = insertTenant(connection);
            long inviterId = insertUser(connection, "inviter");
            long invitedUserId = insertUser(connection, "existing-invitee");
            long verificationUserId = insertUser(connection, "verification");
            long resetUserId = insertUser(connection, "reset");

            insertToken(connection, filledDigest((byte) 1), "INVITATION",
                    null, tenantId, inviterId, "new.user@example.test", EXPIRES_AT, CREATED_AT, null, null);
            insertToken(connection, filledDigest((byte) 2), "INVITATION",
                    invitedUserId, tenantId, inviterId, "existing.user@example.test", EXPIRES_AT, CREATED_AT, null, null);
            insertToken(connection, filledDigest((byte) 3), "EMAIL_VERIFICATION",
                    verificationUserId, null, null, "verification@example.test", EXPIRES_AT, CREATED_AT, null, null);
            insertToken(connection, filledDigest((byte) 4), "PASSWORD_RESET",
                    resetUserId, null, null, "reset@example.test", EXPIRES_AT, CREATED_AT, null, null);

            assertCount(statement,
                    "SELECT COUNT(*) FROM wms_identity_action_tokens",
                    4, "All valid purpose/binding combinations must be accepted");

            assertThrows(SQLException.class,
                    () -> insertToken(connection, filledDigest((byte) 5), "UNKNOWN",
                            verificationUserId, null, null, "unknown@example.test",
                            EXPIRES_AT, CREATED_AT, null, null),
                    "Unknown token purposes must be rejected");
            assertThrows(SQLException.class,
                    () -> insertToken(connection, filledDigest((byte) 6), "INVITATION",
                            null, null, inviterId, "missing-tenant@example.test",
                            EXPIRES_AT, CREATED_AT, null, null),
                    "Invitations must be bound to a tenant");
            assertThrows(SQLException.class,
                    () -> insertToken(connection, filledDigest((byte) 7), "EMAIL_VERIFICATION",
                            null, null, null, "missing-user@example.test",
                            EXPIRES_AT, CREATED_AT, null, null),
                    "Email verification must be bound to a global user");
            assertThrows(SQLException.class,
                    () -> insertToken(connection, filledDigest((byte) 8), "PASSWORD_RESET",
                            resetUserId, tenantId, null, "wrong-binding@example.test",
                            EXPIRES_AT, CREATED_AT, null, null),
                    "Password reset must not carry a tenant or inviter binding");
            assertThrows(SQLException.class,
                    () -> insertToken(connection, filledDigest((byte) 9), "EMAIL_VERIFICATION",
                            verificationUserId, null, null, "terminal@example.test",
                            EXPIRES_AT, CREATED_AT, CREATED_AT, CREATED_AT),
                    "A token cannot be consumed and revoked simultaneously");
            assertThrows(SQLException.class,
                    () -> insertToken(connection, filledDigest((byte) 10), "PASSWORD_RESET",
                            resetUserId, null, null, "expired-at-creation@example.test",
                            CREATED_AT, CREATED_AT, null, null),
                    "Token expiry must be later than creation");
            assertThrows(SQLException.class,
                    () -> insertToken(connection, filledDigest((byte) 11), "EMAIL_VERIFICATION",
                            999999L, null, null, "orphan-user@example.test",
                            EXPIRES_AT, CREATED_AT, null, null),
                    "User foreign keys must reject orphaned action tokens");
            assertThrows(SQLException.class,
                    () -> insertToken(connection, filledDigest((byte) 12), "INVITATION",
                            null, 999999L, inviterId, "orphan-tenant@example.test",
                            EXPIRES_AT, CREATED_AT, null, null),
                    "Tenant foreign keys must reject orphaned action tokens");
            assertThrows(SQLException.class,
                    () -> insertToken(connection, filledDigest((byte) 13), "INVITATION",
                            null, tenantId, 999999L, "orphan-inviter@example.test",
                            EXPIRES_AT, CREATED_AT, null, null),
                    "Inviter foreign keys must reject orphaned action tokens");
            assertThrows(SQLException.class,
                    () -> insertToken(connection, filledDigest((byte) 1), "EMAIL_VERIFICATION",
                            verificationUserId, null, null, "duplicate-digest@example.test",
                            EXPIRES_AT, CREATED_AT, null, null),
                    "A token digest must be globally unique");
        }
    }

    private static long insertTenant(Connection connection) throws SQLException {
        String sql = "INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE')";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, "TOKEN-TEST-" + UUID.randomUUID());
            statement.setString(3, "Identity Token Test Tenant");
            statement.setString(4, "Identity Token Test");
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next(), "Tenant insert must return its generated key");
                return keys.getLong(1);
            }
        }
    }

    private static long insertUser(Connection connection, String suffix) throws SQLException {
        String sql = "INSERT INTO users (public_id, email, password_hash, full_name, status) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE')";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, suffix + "." + UUID.randomUUID() + "@example.test");
            statement.setString(3, "$argon2id$test-only-placeholder");
            statement.setString(4, "Identity Token " + suffix);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next(), "User insert must return its generated key");
                return keys.getLong(1);
            }
        }
    }

    private static void insertToken(
            Connection connection,
            byte[] digest,
            String purpose,
            Long userId,
            Long tenantId,
            Long inviterId,
            String targetEmail,
            String expiresAt,
            String createdAt,
            String consumedAt,
            String revokedAt
    ) throws SQLException {
        String sql = "INSERT INTO wms_identity_action_tokens " +
                "(token_digest, purpose, user_id, tenant_id, invited_by_user_id, target_email, " +
                "expires_at, created_at, consumed_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, digest);
            statement.setString(2, purpose);
            setNullableLong(statement, 3, userId);
            setNullableLong(statement, 4, tenantId);
            setNullableLong(statement, 5, inviterId);
            statement.setString(6, targetEmail);
            statement.setString(7, expiresAt);
            statement.setString(8, createdAt);
            statement.setString(9, consumedAt);
            statement.setString(10, revokedAt);
            statement.executeUpdate();
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static byte[] filledDigest(byte value) {
        byte[] digest = new byte[32];
        java.util.Arrays.fill(digest, value);
        return digest;
    }

    private static void assertCount(Statement statement, String sql, int expected, String message)
            throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), message);
            assertEquals(expected, result.getInt(1), message);
        }
    }
}
