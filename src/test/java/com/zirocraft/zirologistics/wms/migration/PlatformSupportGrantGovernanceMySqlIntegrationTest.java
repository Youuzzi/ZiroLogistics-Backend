package com.zirocraft.zirologistics.wms.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PlatformSupportGrantGovernanceMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_platform_support_test")
            .withUsername("wms_platform_support_test")
            .withPassword("wms_platform_support_test");

    @Test
    void enforcesTenantBoundApprovalAndAllowlistedReadOnlyScopes() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target(MigrationVersion.fromVersion("30"))
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        assertThat(jdbc.queryForObject("""
                SELECT risk_level FROM platform_permissions
                 WHERE permission_code = 'PLATFORM_SUPPORT_GRANT_APPROVE'
                """, String.class)).isEqualTo("CRITICAL");

        long tenantId = createTenant(jdbc);
        long requesterId = createOperator(jdbc);
        long approverId = createOperator(jdbc);
        String grantPublicId = UUID.randomUUID().toString();
        String operationRef = UUID.randomUUID().toString();

        jdbc.update("""
                INSERT INTO platform_support_grants
                  (public_id, tenant_id, requested_by_operator_id, operation_ref, purpose,
                   status, starts_at, expires_at)
                VALUES (?, ?, ?, ?, 'Investigate customer integration health issue',
                        'REQUESTED', DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE),
                        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR))
                """, grantPublicId, tenantId, requesterId, operationRef);
        Long grantId = jdbc.queryForObject(
                "SELECT id FROM platform_support_grants WHERE public_id = ?", Long.class, grantPublicId);
        jdbc.update("""
                INSERT INTO platform_support_grant_scopes (grant_id, scope_code)
                VALUES (?, 'INTEGRATION_DIAGNOSTICS_READ')
                """, grantId);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_support_grants
                 WHERE id = ? AND status = 'REQUESTED' AND approved_by_operator_id IS NULL
                """, Integer.class, grantId)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE platform_support_grants
                   SET status = 'APPROVED', approved_by_operator_id = ?, approved_at = UTC_TIMESTAMP(6)
                 WHERE id = ?
                """, requesterId, grantId)).isInstanceOf(RuntimeException.class);

        jdbc.update("""
                UPDATE platform_support_grants
                   SET status = 'APPROVED', approved_by_operator_id = ?, approved_at = UTC_TIMESTAMP(6)
                 WHERE id = ?
                """, approverId, grantId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_support_grants
                 WHERE id = ? AND status = 'APPROVED' AND approved_by_operator_id = ?
                """, Integer.class, grantId, approverId)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO platform_support_grant_scopes (grant_id, scope_code)
                VALUES (?, 'INVENTORY_READ')
                """, grantId)).isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO platform_support_grants
                  (public_id, tenant_id, requested_by_operator_id, operation_ref, purpose,
                   status, starts_at, expires_at)
                VALUES (?, ?, ?, ?, 'Investigate customer integration health issue',
                        'REQUESTED', UTC_TIMESTAMP(6),
                        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR))
                """, UUID.randomUUID().toString(), Long.MAX_VALUE, requesterId,
                UUID.randomUUID().toString())).isInstanceOf(RuntimeException.class);

        jdbc.update("""
                UPDATE platform_support_grants
                   SET status = 'REVOKED', revoked_by_operator_id = ?, revoked_at = UTC_TIMESTAMP(6)
                 WHERE id = ?
                """, approverId, grantId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM platform_support_grants WHERE id = ?", String.class, grantId))
                .isEqualTo("REVOKED");
    }

    private long createTenant(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Support Test Legal', 'Support Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, UUID.randomUUID().toString(), "SUPPORT_" + UUID.randomUUID().toString().substring(0, 8));
        return jdbc.queryForObject("SELECT id FROM tenants ORDER BY id DESC LIMIT 1", Long.class);
    }

    private long createOperator(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO platform_operators
                  (public_id, email, password_hash, full_name, status, email_verified_at)
                VALUES (?, ?, '$argon2id$integration-test', 'Support Test Operator',
                        'ACTIVE', UTC_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test");
        return jdbc.queryForObject("SELECT id FROM platform_operators ORDER BY id DESC LIMIT 1", Long.class);
    }
}
