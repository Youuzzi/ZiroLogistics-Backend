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
class WmsV11InventoryCoreMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> FRESH_MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v11_fresh_test")
            .withUsername("zirowms_v11_test")
            .withPassword("zirowms_v11_test");

    @Container
    static final MySQLContainer<?> UPGRADE_MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v11_upgrade_test")
            .withUsername("zirowms_v11_test")
            .withPassword("zirowms_v11_test");

    @Test
    void supportsFreshAndV10UpgradePathsWithoutAddingInventoryTables() throws Exception {
        String freshUrl = FRESH_MYSQL.getJdbcUrl();
        String upgradeUrl = UPGRADE_MYSQL.getJdbcUrl();

        Flyway.configure().dataSource(freshUrl, FRESH_MYSQL.getUsername(), FRESH_MYSQL.getPassword())
                .locations("classpath:db/wms-migration").load().migrate();
        assertV11Catalogue(freshUrl, FRESH_MYSQL);

        Flyway.configure().dataSource(upgradeUrl, UPGRADE_MYSQL.getUsername(), UPGRADE_MYSQL.getPassword())
                .locations("classpath:db/wms-migration").target("10").load().migrate();

        String tenantPublicId = UUID.randomUUID().toString();
        String rolePublicId = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(
                upgradeUrl, UPGRADE_MYSQL.getUsername(), UPGRADE_MYSQL.getPassword());
             PreparedStatement tenant = connection.prepareStatement("""
                     INSERT INTO tenants
                       (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                     VALUES (?, 'INVENTORY_MIGRATION_TEST', 'Inventory Migration Legal',
                             'Inventory Migration', 'ACTIVE', 'Asia/Jakarta', 'IDR')
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

        Flyway.configure().dataSource(upgradeUrl, UPGRADE_MYSQL.getUsername(), UPGRADE_MYSQL.getPassword())
                .locations("classpath:db/wms-migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(
                upgradeUrl, UPGRADE_MYSQL.getUsername(), UPGRADE_MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertInventoryPermissions(statement);
            assertEquals(15, scalar(statement, """
                    SELECT COUNT(*) FROM role_permissions rp
                    JOIN roles r ON r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE r.public_id = '""" + rolePublicId + """
                    ' AND p.permission_code IN (
                      'WMS_ITEM_READ', 'WMS_ITEM_CREATE', 'WMS_ITEM_UPDATE', 'WMS_ITEM_STATUS_MANAGE',
                      'WMS_ITEM_CATEGORY_READ', 'WMS_ITEM_CATEGORY_MANAGE',
                      'WMS_ITEM_IDENTIFIER_MANAGE', 'WMS_ITEM_UOM_CONVERSION_MANAGE',
                      'WMS_UOM_CATALOG_READ', 'WMS_LOT_READ', 'WMS_LOT_MANAGE',
                      'WMS_SERIAL_READ', 'WMS_SERIAL_MANAGE',
                      'WMS_INVENTORY_READ', 'WMS_INVENTORY_LEDGER_READ'
                    )
                    """));
            String checkClause = text(statement, """
                    SELECT check_clause FROM information_schema.check_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name = 'chk_tenant_audit_events_type'
                    """);
            assertTrue(checkClause.contains("ITEM_CREATED"));
            assertTrue(checkClause.contains("INVENTORY_SERIAL_STATUS_CHANGED"));
            assertEquals(18, scalar(statement, "SELECT COUNT(*) FROM units_of_measure"));
            assertEquals(69, tableCount(statement));
        }
    }

    private static void assertV11Catalogue(String url, MySQLContainer<?> mysql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                url, mysql.getUsername(), mysql.getPassword());
             Statement statement = connection.createStatement()) {
            assertInventoryPermissions(statement);
            assertEquals(18, scalar(statement, "SELECT COUNT(*) FROM units_of_measure"));
            assertEquals(69, tableCount(statement));
        }
    }

    private static void assertInventoryPermissions(Statement statement) throws Exception {
        assertEquals(15, scalar(statement, """
                SELECT COUNT(*) FROM permissions WHERE permission_code IN (
                  'WMS_ITEM_READ', 'WMS_ITEM_CREATE', 'WMS_ITEM_UPDATE', 'WMS_ITEM_STATUS_MANAGE',
                  'WMS_ITEM_CATEGORY_READ', 'WMS_ITEM_CATEGORY_MANAGE',
                  'WMS_ITEM_IDENTIFIER_MANAGE', 'WMS_ITEM_UOM_CONVERSION_MANAGE',
                  'WMS_UOM_CATALOG_READ', 'WMS_LOT_READ', 'WMS_LOT_MANAGE',
                  'WMS_SERIAL_READ', 'WMS_SERIAL_MANAGE',
                  'WMS_INVENTORY_READ', 'WMS_INVENTORY_LEDGER_READ'
                )
                """));
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
