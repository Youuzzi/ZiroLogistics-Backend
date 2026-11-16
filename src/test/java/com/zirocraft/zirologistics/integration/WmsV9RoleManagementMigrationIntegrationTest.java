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
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class WmsV9RoleManagementMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v9_role_management_test")
            .withUsername("wms_v9_test")
            .withPassword("wms_v9_test");

    @Test
    void addsRolePermissionsAndExtendsAuditAllowlistWithoutChangingEarlierMigrations() throws Exception {
        Flyway beforeV9 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target("8")
                .load();
        beforeV9.migrate();
        beforeV9.validate();

        String tenantPublicId = UUID.randomUUID().toString();
        String rolePublicId = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement tenant = connection.prepareStatement("""
                     INSERT INTO tenants
                       (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                     VALUES (?, 'ROLE_MIGRATION_TEST', 'Role Migration Legal', 'Role Migration',
                             'ACTIVE', 'Asia/Jakarta', 'IDR')
                     """)) {
            tenant.setString(1, tenantPublicId);
            assertEquals(1, tenant.executeUpdate());
            try (PreparedStatement role = connection.prepareStatement("""
                    INSERT INTO roles
                      (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                    SELECT ?, id, 'TENANT_OWNER', 'Tenant Owner', 'Migration test owner', 1, 'ACTIVE'
                    FROM tenants WHERE public_id = ?
                    """)) {
                role.setString(1, rolePublicId);
                role.setString(2, tenantPublicId);
                assertEquals(1, role.executeUpdate());
            }
        }

        Flyway afterV9 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target("9")
                .load();
        afterV9.migrate();
        afterV9.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertEquals(8, scalar(statement, """
                    SELECT COUNT(*) FROM permissions
                    WHERE permission_code IN (
                      'WMS_ROLE_READ', 'WMS_ROLE_CREATE', 'WMS_ROLE_UPDATE',
                      'WMS_ROLE_DEACTIVATE', 'WMS_PERMISSION_CATALOG_READ',
                      'WMS_ROLE_PERMISSION_MANAGE', 'WMS_MEMBERSHIP_ROLE_READ',
                      'WMS_MEMBERSHIP_ROLE_MANAGE'
                    )
                    """));
            assertEquals(8, scalar(statement, """
                    SELECT COUNT(*) FROM role_permissions rp
                    JOIN roles r ON r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE r.public_id = '""" + rolePublicId + """
                    ' AND p.permission_code LIKE 'WMS\\_%' ESCAPE '\\'
                    """));
            String checkClause = text(statement, """
                    SELECT check_clause FROM information_schema.check_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name = 'chk_tenant_audit_events_type'
                    """);
            assertTrue(checkClause.contains("ROLE_CREATED"));
            assertTrue(checkClause.contains("MEMBERSHIP_ROLE_REMOVED"));
            assertEquals(68, scalar(statement, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                      AND table_name <> 'flyway_schema_history'
                    """));
        }
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String text(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
