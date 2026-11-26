package com.zirocraft.zirologistics.wms.migration;

import com.zirocraft.zirologistics.wms.platform.authorization.application.PlatformPermissionEvaluator;
import com.zirocraft.zirologistics.wms.platform.security.PlatformAuthenticatedPrincipal;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PlatformPermissionRiskMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_mfa_risk_test")
            .withUsername("wms_mfa_risk_test")
            .withPassword("wms_mfa_risk_test");

    @Test
    void appliesMfaFreshnessAccordingToPersistedPermissionRisk() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("29"))
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        PlatformPermissionEvaluator evaluator = new PlatformPermissionEvaluator(jdbc);
        PlatformAuthenticatedPrincipal principal =
                new PlatformAuthenticatedPrincipal(UUID.randomUUID().toString());

        jdbc.update("""
                INSERT INTO platform_operators
                  (public_id, email, password_hash, full_name, status, email_verified_at)
                VALUES (?, ?, '$argon2id$integration-test', 'Risk Test Operator', 'ACTIVE', UTC_TIMESTAMP(6))
                """, principal.operatorPublicId(), UUID.randomUUID() + "@example.test");
        Long operatorId = jdbc.queryForObject(
                "SELECT id FROM platform_operators WHERE public_id = ?", Long.class, principal.operatorPublicId());
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM platform_roles WHERE role_code = 'PLATFORM_OWNER'", Long.class);
        Long lowRiskPermissionId = jdbc.queryForObject(
                "SELECT id FROM platform_permissions WHERE permission_code = 'PLATFORM_TENANT_READ'", Long.class);
        Long highRiskPermissionId = jdbc.queryForObject(
                "SELECT id FROM platform_permissions WHERE permission_code = 'PLATFORM_TENANT_SUSPEND'", Long.class);
        Long criticalPermissionId = jdbc.queryForObject(
                "SELECT id FROM platform_permissions WHERE permission_code = 'PLATFORM_TENANT_CLOSE'", Long.class);

        jdbc.update("INSERT INTO platform_role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId, lowRiskPermissionId);
        jdbc.update("INSERT INTO platform_role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId, highRiskPermissionId);
        jdbc.update("INSERT INTO platform_role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId, criticalPermissionId);
        jdbc.update("""
                INSERT INTO platform_operator_roles (operator_id, role_id, assigned_at)
                VALUES (?, ?, UTC_TIMESTAMP(6))
                """, operatorId, roleId);

        assertThat(evaluator.hasPermission(principal, "PLATFORM_TENANT_READ")).isTrue();
        assertThat(evaluator.hasPermission(principal, "PLATFORM_TENANT_SUSPEND")).isFalse();
        assertThat(evaluator.hasPermission(principal, "PLATFORM_TENANT_CLOSE")).isFalse();

        PlatformAuthenticatedPrincipal freshMfa = new PlatformAuthenticatedPrincipal(
                principal.operatorPublicId(), Instant.now().minusSeconds(299));
        PlatformAuthenticatedPrincipal staleMfa = new PlatformAuthenticatedPrincipal(
                principal.operatorPublicId(), Instant.now().minusSeconds(301));
        assertThat(evaluator.hasPermission(freshMfa, "PLATFORM_TENANT_SUSPEND")).isTrue();
        assertThat(evaluator.hasPermission(freshMfa, "PLATFORM_TENANT_CLOSE")).isTrue();
        assertThat(evaluator.hasPermission(staleMfa, "PLATFORM_TENANT_SUSPEND")).isFalse();
        assertThat(evaluator.hasPermission(staleMfa, "PLATFORM_TENANT_CLOSE")).isFalse();
    }
}
