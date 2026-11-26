package com.zirocraft.zirologistics.wms.migration;

import com.zirocraft.zirologistics.wms.authorization.domain.WmsTenantPermissionRiskRegistry;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class WmsTenantPermissionRiskRegistryMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_permission_risk_test")
            .withUsername("wms_permission_risk_test")
            .withPassword("wms_permission_risk_test");

    @Test
    void registryExactlyMatchesTheFlywaySeededTenantPermissionCatalogue() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("30"))
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        Map<String, String> persisted = new TreeMap<>();
        jdbc.query("SELECT permission_code, module FROM permissions ORDER BY permission_code", rs -> {
            persisted.put(rs.getString("permission_code"), rs.getString("module"));
        });

        Map<String, String> registered = new TreeMap<>();
        WmsTenantPermissionRiskRegistry.all().forEach(entry -> registered.put(entry.code(), entry.module()));

        assertThat(registered).containsExactlyInAnyOrderEntriesOf(persisted);
        assertThat(persisted).containsExactlyInAnyOrderEntriesOf(registered);
    }
}
