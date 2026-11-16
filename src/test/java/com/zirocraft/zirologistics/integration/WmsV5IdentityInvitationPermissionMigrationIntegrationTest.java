package com.zirocraft.zirologistics.integration;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class WmsV5IdentityInvitationPermissionMigrationIntegrationTest {

    private static final String PERMISSION_CODE = "WMS_IDENTITY_MEMBERSHIP_INVITE";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v5_invitation_permission_test")
            .withUsername("wms_v5_test")
            .withPassword("wms_v5_test");

    @Test
    void seedsInvitationPermissionWithoutGrantingItToAnyTenantRole() throws Exception {
        Flyway throughV4 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("4"))
                .load();
        throughV4.migrate();
        throughV4.validate();

        Flyway throughV5 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("5"))
                .load();
        throughV5.migrate();
        throughV5.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
                    "AND table_name <> 'flyway_schema_history'",
                    66, "V5 must not add or change the WMS table set");
            assertCount(statement,
                    "SELECT COUNT(*) FROM permissions WHERE permission_code = '" + PERMISSION_CODE + "'",
                    1, "V5 must seed exactly one invitation permission");
            assertCount(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                    "WHERE version IN ('1','2','3','4','5') AND success = 1",
                    5, "Flyway must record V1 through V5 as successful");

            try (ResultSet result = statement.executeQuery("""
                    SELECT module, description
                    FROM permissions
                    WHERE permission_code = 'WMS_IDENTITY_MEMBERSHIP_INVITE'
                    """)) {
                assertTrue(result.next());
                assertEquals("IDENTITY", result.getString("module"));
                assertEquals(
                        "Allows an active tenant membership with this permission to invite a user into the same tenant.",
                        result.getString("description"));
            }

            assertCount(statement,
                    "SELECT COUNT(*) FROM role_permissions rp " +
                    "INNER JOIN permissions p ON p.id = rp.permission_id " +
                    "WHERE p.permission_code = '" + PERMISSION_CODE + "'",
                    0, "V5 must not grant invitation permission to any role by default");

            assertThrows(SQLException.class, () -> insertDuplicatePermission(connection),
                    "The global permission code must remain unique");
        }
    }

    private static void insertDuplicatePermission(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO permissions (permission_code, module, description)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, PERMISSION_CODE);
            statement.setString(2, "IDENTITY");
            statement.setString(3, "duplicate");
            statement.executeUpdate();
        }
    }

    private static void assertCount(Statement statement, String sql, int expected, String message)
            throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), message);
            assertEquals(expected, result.getInt(1), message);
        }
    }
}
