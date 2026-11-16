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
class WmsV10WarehouseMasterDataMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v10_warehouse_test")
            .withUsername("zirowms_v10_test")
            .withPassword("zirowms_v10_test");

    @Test
    void addsWarehousePermissionsAndAuditEventsWithoutChangingEarlierMigrations() throws Exception {
        Flyway beforeV10 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target("9")
                .load();
        beforeV10.migrate();
        beforeV10.validate();

        String tenantPublicId = UUID.randomUUID().toString();
        String rolePublicId = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             PreparedStatement tenant = connection.prepareStatement("""
                     INSERT INTO tenants
                       (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                     VALUES (?, 'WAREHOUSE_MIGRATION_TEST', 'Warehouse Migration Legal',
                             'Warehouse Migration', 'ACTIVE', 'Asia/Jakarta', 'IDR')
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

        Flyway afterV10 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target("10")
                .load();
        afterV10.migrate();
        afterV10.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            assertEquals(8, scalar(statement, """
                    SELECT COUNT(*) FROM permissions
                    WHERE permission_code IN (
                      'WMS_WAREHOUSE_READ', 'WMS_WAREHOUSE_CREATE', 'WMS_WAREHOUSE_UPDATE',
                      'WMS_WAREHOUSE_STATUS_MANAGE', 'WMS_LOCATION_READ', 'WMS_LOCATION_CREATE',
                      'WMS_LOCATION_UPDATE', 'WMS_LOCATION_STATUS_MANAGE'
                    )
                    """));
            assertEquals(8, scalar(statement, """
                    SELECT COUNT(*) FROM role_permissions rp
                    JOIN roles r ON r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE r.public_id = '""" + rolePublicId + """
                    ' AND p.permission_code IN (
                      'WMS_WAREHOUSE_READ', 'WMS_WAREHOUSE_CREATE', 'WMS_WAREHOUSE_UPDATE',
                      'WMS_WAREHOUSE_STATUS_MANAGE', 'WMS_LOCATION_READ', 'WMS_LOCATION_CREATE',
                      'WMS_LOCATION_UPDATE', 'WMS_LOCATION_STATUS_MANAGE'
                    )
                    """));
            String checkClause = text(statement, """
                    SELECT check_clause FROM information_schema.check_constraints
                    WHERE constraint_schema = DATABASE()
                      AND constraint_name = 'chk_tenant_audit_events_type'
                    """);
            assertTrue(checkClause.contains("WAREHOUSE_CREATED"));
            assertTrue(checkClause.contains("WAREHOUSE_LOCATION_STATUS_CHANGED"));
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
