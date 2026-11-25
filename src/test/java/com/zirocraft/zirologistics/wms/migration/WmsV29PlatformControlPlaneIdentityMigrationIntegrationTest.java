package com.zirocraft.zirologistics.wms.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class WmsV29PlatformControlPlaneIdentityMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v29_platform_identity_test")
            .withUsername("wms_v29_test")
            .withPassword("wms_v29_test");

    @Test
    void createsSeparatePlatformIdentityCatalogueWithoutCreatingAnOperatorOrGrantingRoles() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("29"))
                .load();
        flyway.migrate();
        flyway.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM platform_operators"));
            assertEquals(6, scalar(statement, "SELECT COUNT(*) FROM platform_roles"));
            assertEquals(15, scalar(statement, "SELECT COUNT(*) FROM platform_permissions"));
            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM platform_role_permissions"));

            statement.executeUpdate("""
                    INSERT INTO platform_operators (public_id, email, password_hash, full_name, status)
                    VALUES ('%s', 'owner@example.test', '$argon2id$test-only', 'Platform Owner', 'INVITED')
                    """.formatted(UUID.randomUUID()));
            long operatorId = scalar(statement,
                    "SELECT id FROM platform_operators WHERE email = 'owner@example.test'");
            long ownerRoleId = scalar(statement,
                    "SELECT id FROM platform_roles WHERE role_code = 'PLATFORM_OWNER'");

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO platform_operator_roles (operator_id, role_id, assigned_by_operator_id)
                    VALUES (%d, %d, %d)
                    """.formatted(operatorId, ownerRoleId, operatorId)));

            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO platform_operators (public_id, email, password_hash, full_name, status)
                    VALUES ('%s', 'invalid@example.test', '$argon2id$test-only', 'Invalid Operator', 'UNKNOWN')
                    """.formatted(UUID.randomUUID())));
        }
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Expected scalar query result");
            return result.getInt(1);
        }
    }
}
