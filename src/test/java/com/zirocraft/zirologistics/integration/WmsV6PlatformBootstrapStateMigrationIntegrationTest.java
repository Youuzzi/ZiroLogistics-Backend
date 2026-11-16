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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class WmsV6PlatformBootstrapStateMigrationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v6_bootstrap_state_test")
            .withUsername("wms_v6_test")
            .withPassword("wms_v6_test");

    @Test
    void createsExactlyOnePendingSingletonAndRejectsAnotherStateRow() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("6"))
                .load();
        flyway.migrate();
        flyway.validate();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*), MIN(status) FROM wms_platform_bootstrap_state")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
                assertEquals("NOT_COMPLETED", result.getString(2));
            }
            assertThrows(java.sql.SQLException.class, () ->
                    statement.executeUpdate("""
                            INSERT INTO wms_platform_bootstrap_state (id, status)
                            VALUES (2, 'NOT_COMPLETED')
                            """));
            assertThrows(java.sql.SQLException.class, () ->
                    statement.executeUpdate("""
                            UPDATE wms_platform_bootstrap_state
                            SET status = 'COMPLETED'
                            WHERE id = 1
                            """));
        }
    }
}
