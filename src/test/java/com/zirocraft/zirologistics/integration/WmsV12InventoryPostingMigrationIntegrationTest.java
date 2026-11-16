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
class WmsV12InventoryPostingMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> FRESH_MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v12_fresh_test")
            .withUsername("zirowms_v12_test")
            .withPassword("zirowms_v12_test");

    @Container
    static final MySQLContainer<?> UPGRADE_MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v12_upgrade_test")
            .withUsername("zirowms_v12_test")
            .withPassword("zirowms_v12_test");

    @Test
    void supportsFreshAndV11UpgradePathsWithoutChangingExistingInventoryTables() throws Exception {
        String freshUrl = FRESH_MYSQL.getJdbcUrl();
        String upgradeUrl = UPGRADE_MYSQL.getJdbcUrl();

        Flyway.configure().dataSource(freshUrl, FRESH_MYSQL.getUsername(), FRESH_MYSQL.getPassword())
                .locations("classpath:db/wms-migration").load().migrate();
        assertV12Catalogue(freshUrl, FRESH_MYSQL);

        Flyway.configure().dataSource(upgradeUrl, UPGRADE_MYSQL.getUsername(), UPGRADE_MYSQL.getPassword())
                .locations("classpath:db/wms-migration").target("11").load().migrate();

        String tenantPublicId = UUID.randomUUID().toString();
        String ownerRolePublicId = UUID.randomUUID().toString();
        String customRolePublicId = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(
                upgradeUrl, UPGRADE_MYSQL.getUsername(), UPGRADE_MYSQL.getPassword());
             PreparedStatement tenant = connection.prepareStatement("""
                     INSERT INTO tenants
                       (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                     VALUES (?, 'INVENTORY_POST_V12_TEST', 'Inventory Posting Legal',
                             'Inventory Posting', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                     """)) {
            tenant.setString(1, tenantPublicId);
            assertEquals(1, tenant.executeUpdate());
            try (PreparedStatement role = connection.prepareStatement("""
                    INSERT INTO roles
                      (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                    SELECT ?, id, 'TENANT_OWNER', 'Tenant Owner', 'Migration test owner', 1, 'ACTIVE'
                    FROM tenants WHERE public_id = ?
                    """)) {
                role.setString(1, ownerRolePublicId);
                role.setString(2, tenantPublicId);
                assertEquals(1, role.executeUpdate());
            }
            try (PreparedStatement role = connection.prepareStatement("""
                    INSERT INTO roles
                      (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
                    SELECT ?, id, 'INVENTORY_VIEWER', 'Inventory Viewer', 'Must not post stock', 0, 'ACTIVE'
                    FROM tenants WHERE public_id = ?
                    """)) {
                role.setString(1, customRolePublicId);
                role.setString(2, tenantPublicId);
                assertEquals(1, role.executeUpdate());
            }
        }

        Flyway.configure().dataSource(upgradeUrl, UPGRADE_MYSQL.getUsername(), UPGRADE_MYSQL.getPassword())
                .locations("classpath:db/wms-migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(
                upgradeUrl, UPGRADE_MYSQL.getUsername(), UPGRADE_MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement, """
                    SELECT COUNT(*) FROM permissions WHERE permission_code = 'WMS_INVENTORY_POST'
                    """));
            assertEquals(1, scalar(statement, """
                    SELECT COUNT(*) FROM role_permissions rp
                    JOIN roles r ON r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE r.public_id = '""" + ownerRolePublicId + """
                    ' AND p.permission_code = 'WMS_INVENTORY_POST'
                    """));
            assertEquals(0, scalar(statement, """
                    SELECT COUNT(*) FROM role_permissions rp
                    JOIN roles r ON r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE r.public_id = '""" + customRolePublicId + """
                    ' AND p.permission_code = 'WMS_INVENTORY_POST'
                    """));
            String checkClause = text(statement, """
                    SELECT check_clause FROM information_schema.check_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name = 'chk_tenant_audit_events_type'
                    """);
            assertTrue(checkClause.contains("INVENTORY_MOVEMENT_POSTED"));
            assertTrue(checkClause.contains("INVENTORY_SERIAL_STATUS_CHANGED"));
            assertEquals(68, tableCount(statement));
        }
    }

    private static void assertV12Catalogue(String url, MySQLContainer<?> mysql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                url, mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement, """
                    SELECT COUNT(*) FROM permissions WHERE permission_code = 'WMS_INVENTORY_POST'
                    """));
            String checkClause = text(statement, """
                    SELECT check_clause FROM information_schema.check_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name = 'chk_tenant_audit_events_type'
                    """);
            assertTrue(checkClause.contains("INVENTORY_MOVEMENT_POSTED"));
            assertEquals(68, tableCount(statement));
        }
    }

    private static int tableCount(Statement statement) throws Exception {
        return scalar(statement, """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """);
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
