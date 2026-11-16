package com.zirocraft.zirologistics.wms.tenancy;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.tenancy.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsTenantLifecycleMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_tenant_lifecycle_test")
            .withUsername("wms_tenant_lifecycle_test")
            .withPassword("wms_tenant_lifecycle_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsTenantLifecycleService lifecycleService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void suspendsReactivatesAndClosesOnlyThroughAuditedStateTransitions() {
        String tenantPublicId = createTenant("LIFECYCLE_" + UUID.randomUUID().toString().substring(0, 8));
        String suspendRef = UUID.randomUUID().toString();

        WmsTenantLifecycleResult suspended = lifecycleService.transition(
                WmsTenantLifecycleAction.SUSPEND, tenantPublicId,
                "Operator suspension after verified billing review", suspendRef, null);
        assertEquals("ACTIVE", suspended.previousStatus());
        assertEquals("SUSPENDED", suspended.currentStatus());
        assertFalse(suspended.alreadyApplied());
        assertEquals("SUSPENDED", tenantStatus(tenantPublicId));
        assertEquals(1L, tenantVersion(tenantPublicId));

        WmsTenantLifecycleResult repeated = lifecycleService.transition(
                WmsTenantLifecycleAction.SUSPEND, tenantPublicId,
                "Operator suspension after verified billing review", suspendRef, null);
        assertTrue(repeated.alreadyApplied());
        assertEquals(1L, tenantVersion(tenantPublicId));

        WmsTenantLifecycleResult reactivated = lifecycleService.transition(
                WmsTenantLifecycleAction.REACTIVATE, tenantPublicId,
                "Operator reactivation after issue resolution", UUID.randomUUID().toString(), null);
        assertEquals("ACTIVE", reactivated.currentStatus());
        assertEquals(2L, tenantVersion(tenantPublicId));

        assertThrows(WmsTenantLifecycleRejectedException.class, () ->
                lifecycleService.transition(WmsTenantLifecycleAction.CLOSE, tenantPublicId,
                        "Operator closure after tenant requested closure", UUID.randomUUID().toString(),
                        "CLOSE:wrong-code"));
        assertEquals("ACTIVE", tenantStatus(tenantPublicId));

        lifecycleService.transition(WmsTenantLifecycleAction.SUSPEND, tenantPublicId,
                "Operator suspension before approved tenant closure", UUID.randomUUID().toString(), null);
        WmsTenantLifecycleResult closed = lifecycleService.transition(
                WmsTenantLifecycleAction.CLOSE, tenantPublicId,
                "Operator closure after tenant requested closure", UUID.randomUUID().toString(),
                "CLOSE:" + tenantCode(tenantPublicId));
        assertEquals("SUSPENDED", closed.previousStatus());
        assertEquals("CLOSED", closed.currentStatus());
        assertEquals("CLOSED", tenantStatus(tenantPublicId));
        assertEquals(4L, tenantVersion(tenantPublicId));

        assertEquals(4, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE subject_public_id = ? AND actor_type = 'PLATFORM_OPERATOR'
                """, Integer.class, tenantPublicId));
        assertEquals(4, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE subject_public_id = ? AND operation_ref IS NOT NULL
                """, Integer.class, tenantPublicId));
        assertTrue(jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE subject_public_id = ? AND actor_membership_id IS NULL
                  AND event_type = 'TENANT_CLOSED'
                """, Integer.class, tenantPublicId) == 1);
        assertThrows(WmsTenantLifecycleRejectedException.class, () ->
                lifecycleService.transition(WmsTenantLifecycleAction.REACTIVATE, tenantPublicId,
                        "Attempt to reopen a closed tenant is forbidden", UUID.randomUUID().toString(), null));
    }

    @Test
    void rejectsDirectActiveToClosedTransitionAndInvalidOperationReference() {
        String tenantPublicId = createTenant("LIFECYCLE_" + UUID.randomUUID().toString().substring(0, 8));
        assertThrows(WmsTenantLifecycleRejectedException.class, () ->
                lifecycleService.transition(WmsTenantLifecycleAction.CLOSE, tenantPublicId,
                        "Direct closure from active state is forbidden", UUID.randomUUID().toString(),
                        "CLOSE:" + tenantCode(tenantPublicId)));
        assertThrows(WmsTenantLifecycleRejectedException.class, () ->
                lifecycleService.transition(WmsTenantLifecycleAction.SUSPEND, tenantPublicId,
                        "Valid operator reason for state change", "not-a-uuid", null));
        assertEquals("ACTIVE", tenantStatus(tenantPublicId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events WHERE subject_public_id = ?
                """, Integer.class, tenantPublicId));
    }

    private String createTenant(String code) {
        String publicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'Lifecycle Test Legal', 'Lifecycle Test', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, publicId, code);
        return publicId;
    }

    private String tenantStatus(String publicId) {
        return jdbc.queryForObject("SELECT status FROM tenants WHERE public_id = ?", String.class, publicId);
    }

    private String tenantCode(String publicId) {
        return jdbc.queryForObject("SELECT tenant_code FROM tenants WHERE public_id = ?", String.class, publicId);
    }

    private long tenantVersion(String publicId) {
        return jdbc.queryForObject("SELECT version FROM tenants WHERE public_id = ?", Long.class, publicId);
    }
}
