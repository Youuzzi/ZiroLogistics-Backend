package com.zirocraft.zirologistics.integration;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class WmsV6PlatformBootstrapMigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v6_platform_bootstrap_test")
            .withUsername("wms_v6_test")
            .withPassword("wms_v6_test");

    @Test
    void addsSingletonBootstrapStateWithoutChangingV1ToV5() throws Exception {
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
                    66, "V1-V5 must remain the established 66-table schema");
            assertCount(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                            "WHERE version IN ('1','2','3','4','5') AND success = 1",
                    5, "V1 through V5 must be successful before V6");

            Flyway throughV6 = Flyway.configure()
                    .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                    .locations("classpath:db/wms-migration")
                    .load();
            throughV6.migrate();
            throughV6.validate();

            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                            "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' " +
                            "AND table_name <> 'flyway_schema_history'",
                    67, "V6 must add exactly one bootstrap state table");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.referential_constraints " +
                            "WHERE constraint_schema = DATABASE()",
                    170, "V6 must add exactly two restrictive foreign keys");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.table_constraints " +
                            "WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK'",
                    146, "V6 must add exactly two CHECK constraints");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.columns " +
                            "WHERE table_schema = DATABASE() AND table_name = 'wms_platform_bootstrap_state'",
                    5, "Bootstrap state must expose the reviewed five-column contract");
            assertCount(statement,
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                            "WHERE table_schema = DATABASE() AND table_name = 'wms_platform_bootstrap_state'",
                    3, "Bootstrap state must expose one primary and two unique indexes");
            assertCount(statement,
                    "SELECT COUNT(*) FROM wms_platform_bootstrap_state WHERE id = 1",
                    1, "V6 must seed exactly one singleton state row");
            assertCount(statement,
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                            "WHERE version IN ('1','2','3','4','5','6') AND success = 1",
                    6, "Flyway must record V1 through V6 as successful");

            assertThrows(SQLException.class,
                    () -> statement.executeUpdate(
                            "INSERT INTO wms_platform_bootstrap_state (id) VALUES (2)"),
                    "Only singleton state id 1 is allowed");
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate(
                            "UPDATE wms_platform_bootstrap_state SET completed_at = UTC_TIMESTAMP(6) WHERE id = 1"),
                    "Completion requires both tenant and user foreign keys");
        }
    }

    private static void assertCount(Statement statement, String sql, int expected, String message)
            throws Exception {
        try (var result = statement.executeQuery(sql)) {
            assertTrue(result.next(), message);
            assertEquals(expected, result.getInt(1), message);
        }
    }
}
