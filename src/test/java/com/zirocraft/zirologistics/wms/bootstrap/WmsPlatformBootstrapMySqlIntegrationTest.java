package com.zirocraft.zirologistics.wms.bootstrap;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.authorization.domain.WmsTenantPermissionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsPlatformBootstrapMySqlIntegrationTest {
    private static final String RAW_PASSWORD = "Bootstrap-Only-Secret-2026!";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_bootstrap_runtime_test")
            .withUsername("wms_bootstrap_test")
            .withPassword("wms_bootstrap_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsPlatformBootstrapService service;
    @Autowired private WmsBootstrapProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WmsBootstrapCommandRunner commandRunner;

    @BeforeEach
    void resetDisposableDatabase() {
        jdbc.update("DELETE FROM role_permissions");
        jdbc.update("DELETE FROM membership_roles");
        jdbc.update("DELETE FROM roles");
        jdbc.update("DELETE FROM tenant_memberships");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM tenants");
        jdbc.update("""
                INSERT IGNORE INTO permissions (permission_code, module, description)
                VALUES ('WMS_IDENTITY_MEMBERSHIP_INVITE', 'IDENTITY',
                        'Allows an active tenant membership with this permission to invite a user into the same tenant.')
                """);
        jdbc.update("""
                UPDATE wms_platform_bootstrap_state
                SET status='NOT_COMPLETED', operation_ref=NULL, completed_at=NULL
                WHERE id=1
                """);
        properties.setEnabled(true);
        properties.setTenantCode("BOOTSTRAP_TEST");
        properties.setTenantLegalName("Bootstrap Test Legal Entity");
        properties.setTenantDisplayName("Bootstrap Test");
        properties.setTimezone("Asia/Jakarta");
        properties.setCurrency("IDR");
        properties.setOperatorEmail("bootstrap." + UUID.randomUUID() + "@example.test");
        properties.setOperatorFullName("WMS Bootstrap Operator");
        properties.setOperatorPassword(RAW_PASSWORD);
    }

    @Test
    void createsTenantOperatorMembershipAndExplicitPermissionAtomically() {
        WmsPlatformBootstrapService.Result result = service.bootstrap(properties);

        assertEquals(1, count("users"));
        assertEquals(1, count("tenants"));
        assertEquals(1, count("tenant_memberships"));
        assertEquals(1 + WmsTenantPermissionPolicy.systemRoleTemplates().size(), count("roles"));
        assertEquals(1, count("membership_roles"));
        assertEquals(WmsTenantPermissionPolicy.tenantOwnerTemplate().size(), jdbc.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                WHERE r.public_id = ?
                """, Integer.class, result.rolePublicId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM wms_platform_bootstrap_state WHERE status='COMPLETED' AND operation_ref=?",
                Integer.class, result.operationRef()));

        String passwordHash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE public_id=?", String.class, result.userPublicId());
        assertNotEquals(RAW_PASSWORD, passwordHash);
        assertTrue(passwordHash.startsWith("$argon2"));
        assertTrue(passwordEncoder.matches(RAW_PASSWORD, passwordHash));

        java.util.List<String> actualOwnerPermissions = jdbc.queryForList("""
                SELECT p.permission_code
                FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE r.public_id = ?
                ORDER BY p.permission_code
                """, String.class, result.rolePublicId());
        assertEquals(WmsTenantPermissionPolicy.tenantOwnerTemplate().stream().sorted().toList(),
                actualOwnerPermissions);
        assertFalse(actualOwnerPermissions.contains("WMS_INVENTORY_POST"));

        java.util.List<String> actualSystemRoleCodes = jdbc.queryForList("""
                SELECT role_code FROM roles
                WHERE tenant_id = (SELECT id FROM tenants WHERE public_id = ?)
                  AND is_system_role = 1 AND role_code <> 'TENANT_OWNER'
                ORDER BY role_code
                """, String.class, result.tenantPublicId());
        assertEquals(WmsTenantPermissionPolicy.systemRoleTemplates().stream()
                .map(WmsTenantPermissionPolicy.RoleTemplate::code).sorted().toList(), actualSystemRoleCodes);
        for (WmsTenantPermissionPolicy.RoleTemplate template
                : WmsTenantPermissionPolicy.systemRoleTemplates()) {
            java.util.List<String> actualTemplatePermissions = jdbc.queryForList("""
                    SELECT p.permission_code
                    FROM roles r
                    JOIN role_permissions rp ON rp.role_id = r.id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE r.tenant_id = (SELECT id FROM tenants WHERE public_id = ?)
                      AND r.role_code = ? AND r.is_system_role = 1
                    ORDER BY p.permission_code
                    """, String.class, result.tenantPublicId(), template.code());
            assertEquals(template.permissionCodes().stream().sorted().toList(), actualTemplatePermissions,
                    "Persisted grants differ for system role " + template.code());
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM users
                WHERE public_id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL
                """, Integer.class, result.userPublicId()));

        assertThrows(WmsPlatformBootstrapService.BootstrapRejectedException.class,
                () -> service.bootstrap(properties));
        assertEquals(1, count("users"));
        assertEquals(1, count("tenants"));
    }

    @Test
    void refusesNonEmptyDatabaseWithoutCreatingAnyBootstrapRecords() {
        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, 'EXISTING', 'Existing Legal Entity', 'Existing', 'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, UUID.randomUUID().toString());

        assertThrows(WmsPlatformBootstrapService.BootstrapRejectedException.class,
                () -> service.bootstrap(properties));
        assertEquals(0, count("users"));
        assertEquals(1, count("tenants"));
        assertEquals("NOT_COMPLETED", jdbc.queryForObject(
                "SELECT status FROM wms_platform_bootstrap_state WHERE id=1", String.class));
    }


    @Test
    void rollsBackAllCreatedRowsWhenTheReviewedPermissionIsMissing() {
        jdbc.update("DELETE FROM permissions WHERE permission_code='WMS_IDENTITY_MEMBERSHIP_INVITE'");

        assertThrows(RuntimeException.class, () -> service.bootstrap(properties));
        assertEquals(0, count("users"));
        assertEquals(0, count("tenants"));
        assertEquals(0, count("tenant_memberships"));
        assertEquals(0, count("roles"));
        assertEquals("NOT_COMPLETED", jdbc.queryForObject(
                "SELECT status FROM wms_platform_bootstrap_state WHERE id=1", String.class));
    }

    @Test
    void concurrentInvocationsAllowExactlyOneBootstrapToCommit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> attemptAfter(start));
            Future<Boolean> second = pool.submit(() -> attemptAfter(start));
            start.countDown();

            assertNotEquals(first.get(), second.get());
            assertEquals(1, count("users"));
            assertEquals(1, count("tenants"));
            assertEquals(1, count("tenant_memberships"));
            assertEquals(1 + WmsTenantPermissionPolicy.systemRoleTemplates().size(), count("roles"));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wms_platform_bootstrap_state WHERE status='COMPLETED'",
                    Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidConfigurationFailsBeforeAnyDatabaseWrite() {
        properties.setOperatorPassword("short");

        assertThrows(IllegalArgumentException.class, () -> service.bootstrap(properties));
        assertEquals(0, count("users"));
        assertEquals(0, count("tenants"));
        assertEquals("NOT_COMPLETED", jdbc.queryForObject(
                "SELECT status FROM wms_platform_bootstrap_state WHERE id=1", String.class));
    }

    @Test
    void rejectsFixedOffsetTimezoneBeforeAnyDatabaseWrite() {
        properties.setTimezone("+07:00");

        assertThrows(IllegalArgumentException.class, () -> service.bootstrap(properties));
        assertEquals(0, count("users"));
        assertEquals(0, count("tenants"));
        assertEquals("NOT_COMPLETED", jdbc.queryForObject(
                "SELECT status FROM wms_platform_bootstrap_state WHERE id=1", String.class));
    }

    @Test
    void rejectsUnknownCurrencyBeforeAnyDatabaseWrite() {
        properties.setCurrency("ZZZ");

        assertThrows(IllegalArgumentException.class, () -> service.bootstrap(properties));
        assertEquals(0, count("users"));
        assertEquals(0, count("tenants"));
        assertEquals("NOT_COMPLETED", jdbc.queryForObject(
                "SELECT status FROM wms_platform_bootstrap_state WHERE id=1", String.class));
    }

    private boolean attemptAfter(CountDownLatch start) throws Exception {
        start.await();
        try {
            service.bootstrap(properties);
            return true;
        } catch (WmsPlatformBootstrapService.BootstrapRejectedException expected) {
            return false;
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
