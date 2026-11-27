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

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class WmsV32TenantMfaReauthenticationGrantMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v32_tenant_mfa_reauth_test")
            .withUsername("wms_v32_test")
            .withPassword("wms_v32_test");

    @Test
    void createsSingleUseIdentityAndPurposeBoundReauthenticationGrantStorage() throws Exception {
        Flyway beforeV32 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("31"))
                .load();
        beforeV32.migrate();
        beforeV32.validate();

        Flyway current = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .load();
        current.migrate();
        current.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'wms_tenant_mfa_reauthentication_grants'
                      AND table_type = 'BASE TABLE'
                    """));
            assertEquals(1, scalar(statement, """
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema = DATABASE()
                      AND table_name = 'wms_tenant_mfa_reauthentication_grants'
                      AND index_name = 'uk_wms_tenant_mfa_reauth_digest'
                      AND non_unique = 0
                    """));
            assertEquals(3, scalar(statement, """
                    SELECT COUNT(*) FROM information_schema.check_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name IN (
                        'chk_wms_tenant_mfa_reauth_purpose',
                        'chk_wms_tenant_mfa_reauth_expiry',
                        'chk_wms_tenant_mfa_reauth_terminal_state'
                      )
                    """));
            assertEquals(4, scalar(statement, """
                    SELECT COUNT(*) FROM information_schema.key_column_usage
                    WHERE table_schema = DATABASE()
                      AND table_name = 'wms_tenant_mfa_reauthentication_grants'
                      AND referenced_table_name IS NOT NULL
                    """));
        }
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("Expected scalar query result");
            return result.getInt(1);
        }
    }
}
