package com.zirocraft.zirologistics.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class WmsV7TenantProfilePermissionMigrationIntegrationTest {
    private static final String PERMISSION = "WMS_TENANT_PROFILE_UPDATE";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v7_tenant_permission_test")
            .withUsername("wms_v7_test")
            .withPassword("wms_v7_test");

    @Test
    void seedsTenantProfilePermissionWithoutDefaultRoleGrant() throws Exception {
        Flyway throughV6 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("6"))
                .load();
        throughV6.migrate();
        throughV6.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO tenants
                      (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                    VALUES ('11111111-1111-4111-8111-111111111111', 'V7_EXISTING',
                            'Existing Tenant Legal', 'Existing Tenant', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                    """);
            statement.executeUpdate("""
                    INSERT INTO roles
                      (public_id, tenant_id, role_code, role_name, is_system_role, status)
                    SELECT '22222222-2222-4222-8222-222222222222', id, 'TENANT_OWNER',
                           'Tenant Owner', 1, 'ACTIVE'
                    FROM tenants WHERE tenant_code = 'V7_EXISTING'
                    """);
            statement.executeUpdate("""
                    INSERT INTO roles
                      (public_id, tenant_id, role_code, role_name, is_system_role, status)
                    SELECT '33333333-3333-4333-8333-333333333333', id, 'CUSTOM_MANAGER',
                           'Custom Manager', 0, 'ACTIVE'
                    FROM tenants WHERE tenant_code = 'V7_EXISTING'
                    """);
        }

        Flyway throughV7 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("7"))
                .load();
        throughV7.migrate();
        throughV7.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertCount(statement,
                    "SELECT COUNT(*) FROM permissions WHERE permission_code = '" + PERMISSION + "'",
                    1);
            assertCount(statement,
                    "SELECT COUNT(*) FROM role_permissions rp " +
                            "JOIN permissions p ON p.id = rp.permission_id " +
                            "JOIN roles r ON r.id = rp.role_id " +
                            "WHERE p.permission_code = '" + PERMISSION + "' " +
                            "AND r.role_code = 'TENANT_OWNER'",
                    1);
            assertCount(statement,
                    "SELECT COUNT(*) FROM role_permissions rp " +
                            "JOIN permissions p ON p.id = rp.permission_id " +
                            "JOIN roles r ON r.id = rp.role_id " +
                            "WHERE p.permission_code = '" + PERMISSION + "' " +
                            "AND r.role_code = 'CUSTOM_MANAGER'",
                    0);
            try (ResultSet rs = statement.executeQuery(
                    "SELECT module FROM permissions WHERE permission_code = '" + PERMISSION + "'")) {
                assertTrue(rs.next());
                assertEquals("TENANCY", rs.getString(1));
            }
            assertCount(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6','7') AND success=1",
                    7);
        }
    }

    private static void assertCount(Statement statement, String sql, int expected) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            assertEquals(expected, rs.getInt(1));
        }
    }
}
