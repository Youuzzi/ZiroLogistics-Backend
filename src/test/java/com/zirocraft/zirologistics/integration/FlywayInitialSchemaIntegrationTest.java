package com.zirocraft.zirologistics.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class FlywayInitialSchemaIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirologistics_migration_test")
            .withUsername("migration_test")
            .withPassword("migration_test");

    @Test
    void migratesAnEmptyMySqlDatabaseAndCreatesExpectedSchema() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {

            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name IN " +
                    "('users', 'warehouses', 'items', 'bins', 'inventory_stocks', 'inventory_ledger')")) {
                assertTrue(result.next());
                assertEquals(6, result.getInt(1), "All six application tables must be created");
            }

            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                    "WHERE version = '1' AND success = 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1), "Flyway V1 must be recorded as successful");
            }

            try (ResultSet result = statement.executeQuery(
                    "SELECT IS_NULLABLE FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = 'items' " +
                    "AND column_name = 'min_stock_level'")) {
                assertTrue(result.next());
                assertEquals("NO", result.getString(1),
                        "min_stock_level must match ItemEntity's non-null contract");
            }

            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.referential_constraints " +
                    "WHERE constraint_schema = DATABASE()")) {
                assertTrue(result.next());
                assertEquals(5, result.getInt(1), "All five foreign-key relationships must exist");
            }

            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() AND table_name = 'inventory_ledger'")) {
                assertTrue(result.next());
                assertFalse(result.getInt(1) == 0, "Inventory ledger table must exist");
            }
        }
    }
}
