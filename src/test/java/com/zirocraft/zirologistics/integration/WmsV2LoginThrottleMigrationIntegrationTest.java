package com.zirocraft.zirologistics.integration;

import org.flywaydb.core.Flyway;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class WmsV2LoginThrottleMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v2_throttle_test")
            .withUsername("wms_v2_test")
            .withPassword("wms_v2_test");

    @Test
    void migratesAdditiveThrottleTableWithoutChangingFrozenV1() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .load();
        flyway.migrate();
        flyway.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
                    "AND table_name <> 'flyway_schema_history'",
                    64, "V2 must add exactly one table to the 63-table V1 schema");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.referential_constraints " +
                    "WHERE constraint_schema = DATABASE()",
                    165, "V2 must not add or alter V1 foreign keys");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                    "WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK'",
                    134, "V2 must add exactly three CHECK constraints to V1");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = 'wms_login_throttle_buckets' " +
                    "AND engine = 'InnoDB' AND table_collation = 'utf8mb4_0900_ai_ci'",
                    1, "V2 throttle table must use InnoDB and the frozen WMS collation");
            assertCount(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                    "WHERE version IN ('1','2') AND success = 1",
                    2, "Flyway must record both V1 and V2 as successful");

            insertBucket(connection, "ACCOUNT", 1, new byte[32], null);
            insertBucket(connection, "SOURCE", 1, filledDigest((byte) 1), null);

            assertThrows(SQLException.class,
                    () -> insertBucket(connection, "SOURCE", 1, filledDigest((byte) 2),
                            "2030-01-01 00:00:00.000000"),
                    "SOURCE buckets must not carry a cooldown");
            assertThrows(SQLException.class,
                    () -> insertBucket(connection, "OTHER", 1, filledDigest((byte) 3), null),
                    "Only ACCOUNT and SOURCE bucket types are valid");
            assertThrows(SQLException.class,
                    () -> insertBucket(connection, "ACCOUNT", 0, filledDigest((byte) 4), null),
                    "HMAC key version must be positive");
            assertThrows(SQLException.class,
                    () -> insertBucket(connection, "ACCOUNT", 1, new byte[32], null),
                    "Duplicate logical bucket/window identities must be rejected");
        }
    }

    private static void insertBucket(
            Connection connection,
            String type,
            int keyVersion,
            byte[] digest,
            String blockedUntil
    ) throws SQLException {
        String sql = "INSERT INTO wms_login_throttle_buckets " +
                "(bucket_type, key_version, bucket_key_hash, window_started_at, attempt_count, blocked_until) " +
                "VALUES (?, ?, ?, '2026-11-16 00:00:00.000000', 0, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type);
            statement.setInt(2, keyVersion);
            statement.setBytes(3, digest);
            statement.setString(4, blockedUntil);
            statement.executeUpdate();
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
