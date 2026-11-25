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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PlatformPermissionEvaluatorMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_platform_permission_test")
            .withUsername("wms_platform_permission_test")
            .withPassword("wms_platform_permission_test");

    @Test
    void evaluatesCurrentPersistedOperatorAssignmentRoleAndPermissionState() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("29"))
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        PlatformPermissionEvaluator evaluator = new PlatformPermissionEvaluator(jdbc);
        PlatformAuthenticatedPrincipal principal = new PlatformAuthenticatedPrincipal(UUID.randomUUID().toString());

        jdbc.update("""
                INSERT INTO platform_operators
                    (public_id, email, password_hash, full_name, status, email_verified_at)
                VALUES (?, ?, '$argon2id$integration-test', 'Integration Operator', 'ACTIVE', UTC_TIMESTAMP(6))
                """, principal.operatorPublicId(), UUID.randomUUID() + "@example.test");

        Long operatorId = jdbc.queryForObject(
                "SELECT id FROM platform_operators WHERE public_id = ?", Long.class,
                principal.operatorPublicId());
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM platform_roles WHERE role_code = 'PLATFORM_OWNER'", Long.class);
        Long permissionId = jdbc.queryForObject(
                "SELECT id FROM platform_permissions WHERE permission_code = 'PLATFORM_TENANT_READ'", Long.class);

        assertThat(operatorId).isNotNull();
        assertThat(roleId).isNotNull();
        assertThat(permissionId).isNotNull();

        jdbc.update("""
                INSERT INTO platform_role_permissions (role_id, permission_id)
                VALUES (?, ?)
                """, roleId, permissionId);
        jdbc.update("""
                INSERT INTO platform_operator_roles
                    (operator_id, role_id, assigned_at, expires_at)
                VALUES (?, ?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY),
                        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY))
                """, operatorId, roleId);

        // An expired persisted assignment must not authorize, even if all catalogue rows are active.
        assertThat(evaluator.hasPermission(principal, "PLATFORM_TENANT_READ")).isFalse();

        jdbc.update("""
                UPDATE platform_operator_roles
                   SET expires_at = NULL
                 WHERE operator_id = ? AND role_id = ?
                """, operatorId, roleId);
        assertThat(evaluator.hasPermission(principal, "PLATFORM_TENANT_READ")).isTrue();

        jdbc.update("""
                UPDATE platform_operator_roles
                   SET revoked_at = UTC_TIMESTAMP(6)
                 WHERE operator_id = ? AND role_id = ?
                """, operatorId, roleId);
        assertThat(evaluator.hasPermission(principal, "PLATFORM_TENANT_READ")).isFalse();

        jdbc.update("""
                UPDATE platform_operator_roles
                   SET revoked_at = NULL
                 WHERE operator_id = ? AND role_id = ?
                """, operatorId, roleId);
        jdbc.update("UPDATE platform_roles SET status = 'INACTIVE' WHERE id = ?", roleId);
        assertThat(evaluator.hasPermission(principal, "PLATFORM_TENANT_READ")).isFalse();

        jdbc.update("UPDATE platform_roles SET status = 'ACTIVE' WHERE id = ?", roleId);
        jdbc.update("UPDATE platform_permissions SET status = 'INACTIVE' WHERE id = ?", permissionId);
        assertThat(evaluator.hasPermission(principal, "PLATFORM_TENANT_READ")).isFalse();

        jdbc.update("UPDATE platform_permissions SET status = 'ACTIVE' WHERE id = ?", permissionId);
        jdbc.update("UPDATE platform_operators SET status = 'SUSPENDED' WHERE id = ?", operatorId);
        assertThat(evaluator.hasPermission(principal, "PLATFORM_TENANT_READ")).isFalse();

        jdbc.update("UPDATE platform_operators SET status = 'ACTIVE', email_verified_at = NULL WHERE id = ?",
                operatorId);
        assertThat(evaluator.hasPermission(principal, "PLATFORM_TENANT_READ")).isFalse();
    }
}
