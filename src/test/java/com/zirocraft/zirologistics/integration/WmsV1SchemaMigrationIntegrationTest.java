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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class WmsV1SchemaMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v1_migration_test")
            .withUsername("wms_migration_test")
            .withPassword("wms_migration_test");

    @Test
    void migratesFrozenWmsSchemaOnFreshMySql84Database() throws Exception {
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
                    63, "WMS V1 must create exactly 63 tables");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.referential_constraints " +
                    "WHERE constraint_schema = DATABASE()",
                    165, "WMS V1 must create all 165 foreign keys");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                    "WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK'",
                    131, "WMS V1 must create all 131 CHECK constraints");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
                    "AND table_name <> 'flyway_schema_history' " +
                    "AND (table_collation IS NULL OR table_collation <> 'utf8mb4_0900_ai_ci')",
                    0, "Every WMS table must use the frozen collation");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
                    "AND table_name <> 'flyway_schema_history' " +
                    "AND engine <> 'InnoDB'",
                    0, "Every WMS table must use InnoDB");
            assertCount(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                    "WHERE version = '1' AND success = 1",
                    1, "Flyway must record WMS V1 as successful");
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
