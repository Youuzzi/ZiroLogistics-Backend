package com.zirocraft.zirologistics.wms.tenancy;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProvisioningResult;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProvisioningService;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.config.WmsTenantProvisioningProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsTenantProvisioningMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_tenant_provisioning_test")
            .withUsername("wms_tenant_provisioning_test")
            .withPassword("wms_tenant_provisioning_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsTenantProvisioningService provisioningService;
    @Autowired private WmsTenantProvisioningProperties properties;
    @Autowired private UserRepository userRepository;
    @Autowired private WmsMembershipAccessService membershipAccessService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void provisionsNewTenantWithPendingVerifiedOwnerAndExplicitMinimumRoleGrants() {
        configure("TENANT_" + UUID.randomUUID().toString().substring(0, 8),
                "new-owner-" + UUID.randomUUID() + "@example.test");
        WmsTenantProvisioningResult result = provisioningService.provision(properties);

        assertTrue(result.ownerVerificationRequired());
        assertFalse(result.alreadyProvisioned());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM tenants WHERE public_id = ?", String.class, result.tenantPublicId()));
        assertEquals("PENDING_VERIFICATION", jdbc.queryForObject(
                "SELECT status FROM users WHERE public_id = ?", String.class, result.ownerUserPublicId()));
        assertNull(jdbc.queryForObject(
                "SELECT email_verified_at FROM users WHERE public_id = ?",
                java.sql.Timestamp.class, result.ownerUserPublicId()));
        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE public_id = ?", String.class, result.ownerUserPublicId());
        assertTrue(hash.startsWith("$argon2"));
        assertFalse(passwordEncoder.matches("password", hash));

        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_memberships
                WHERE public_id = ? AND status = 'ACTIVE'
                """, Integer.class, result.ownerMembershipPublicId()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM membership_roles mr
                JOIN role_permissions rp ON rp.role_id = mr.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE mr.membership_id = (
                  SELECT id FROM tenant_memberships WHERE public_id = ?
                )
                AND p.permission_code IN (
                  'WMS_IDENTITY_MEMBERSHIP_INVITE',
                  'WMS_TENANT_PROFILE_UPDATE'
                )
                """, Integer.class, result.ownerMembershipPublicId()));
        assertEquals(8, jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM membership_roles mr
                JOIN role_permissions rp ON rp.role_id = mr.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE mr.membership_id = (
                  SELECT id FROM tenant_memberships WHERE public_id = ?
                )
                AND p.permission_code IN (
                  'WMS_ROLE_READ', 'WMS_ROLE_CREATE', 'WMS_ROLE_UPDATE',
                  'WMS_ROLE_DEACTIVATE', 'WMS_PERMISSION_CATALOG_READ',
                  'WMS_ROLE_PERMISSION_MANAGE', 'WMS_MEMBERSHIP_ROLE_READ',
                  'WMS_MEMBERSHIP_ROLE_MANAGE'
                )
                """, Integer.class, result.ownerMembershipPublicId()));
        assertEquals(8, jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM membership_roles mr
                JOIN role_permissions rp ON rp.role_id = mr.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE mr.membership_id = (
                  SELECT id FROM tenant_memberships WHERE public_id = ?
                )
                AND p.permission_code IN (
                  'WMS_WAREHOUSE_READ', 'WMS_WAREHOUSE_CREATE', 'WMS_WAREHOUSE_UPDATE',
                  'WMS_WAREHOUSE_STATUS_MANAGE', 'WMS_LOCATION_READ', 'WMS_LOCATION_CREATE',
                  'WMS_LOCATION_UPDATE', 'WMS_LOCATION_STATUS_MANAGE'
                )
                """, Integer.class, result.ownerMembershipPublicId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM membership_roles mr
                JOIN role_permissions rp ON rp.role_id = mr.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE mr.membership_id = (
                  SELECT id FROM tenant_memberships WHERE public_id = ?
                )
                AND p.permission_code = 'WMS_INVENTORY_POST'
                """, Integer.class, result.ownerMembershipPublicId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM membership_roles mr
                JOIN role_permissions rp ON rp.role_id = mr.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE mr.membership_id = (
                  SELECT id FROM tenant_memberships WHERE public_id = ?
                )
                AND p.permission_code = 'WMS_OPERATIONAL_REPORT_READ'
                """, Integer.class, result.ownerMembershipPublicId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE operation_ref = ? AND event_type = 'TENANT_PROVISIONED'
                  AND actor_type = 'PLATFORM_OPERATOR'
                  AND actor_reference = 'operator-console:production'
                """, Integer.class, result.operationRef()));

        assertThrows(WmsMembershipAccessDeniedException.class, () ->
                membershipAccessService.resolve(result.ownerUserPublicId(), result.ownerMembershipPublicId()));

        WmsTenantProvisioningResult repeated = provisioningService.provision(properties);
        assertTrue(repeated.alreadyProvisioned());
        assertEquals(result.tenantPublicId(), repeated.tenantPublicId());
        assertEquals(result.ownerUserPublicId(), repeated.ownerUserPublicId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE tenant_code = ?",
                Integer.class, properties.getTenantCode()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audit_events WHERE operation_ref = ?",
                Integer.class, result.operationRef()));

        properties.setLegalName("Different Legal Entity");
        assertThrows(RuntimeException.class, () -> provisioningService.provision(properties));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE tenant_code = ?",
                Integer.class, properties.getTenantCode()));
    }

    @Test
    void addsExistingVerifiedGlobalUserWithoutDuplicatingIdentity() {
        String suffix = UUID.randomUUID().toString();
        UserEntity existing = new UserEntity();
        existing.setEmail("existing-owner-" + suffix + "@example.test");
        existing.setPasswordHash("$argon2id$test-only");
        existing.setFullName("Existing Tenant Owner");
        existing.setStatus(UserStatus.ACTIVE);
        existing.setEmailVerifiedAt(LocalDateTime.now());
        existing = userRepository.saveAndFlush(existing);
        long usersBefore = userRepository.count();

        configure("TENANT_" + suffix.substring(0, 8),
                existing.getEmail());
        WmsTenantProvisioningResult result = provisioningService.provision(properties);

        assertFalse(result.ownerVerificationRequired());
        assertEquals(usersBefore, userRepository.count());
        assertEquals(existing.getPublicId(), result.ownerUserPublicId());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM tenant_memberships WHERE public_id = ?",
                String.class, result.ownerMembershipPublicId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM membership_roles mr
                JOIN roles r ON r.id = mr.role_id AND r.tenant_id = mr.tenant_id
                WHERE mr.membership_id = ? AND r.role_code = 'TENANT_OWNER'
                """, Integer.class,
                jdbc.queryForObject("SELECT id FROM tenant_memberships WHERE public_id = ?",
                        Long.class, result.ownerMembershipPublicId())));
    }

    @Test
    void rejectsExistingUnverifiedOwnerAndDisabledCommandBeforeTenantWrites() {
        String email = "pending-owner-" + UUID.randomUUID() + "@example.test";
        UserEntity pending = new UserEntity();
        pending.setEmail(email);
        pending.setPasswordHash("$argon2id$test-only");
        pending.setFullName("Pending Owner");
        pending.setStatus(UserStatus.PENDING_VERIFICATION);
        userRepository.saveAndFlush(pending);

        configure("TENANT_" + UUID.randomUUID().toString().substring(0, 8), email);
        assertThrows(RuntimeException.class, () -> provisioningService.provision(properties));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE tenant_code = ?",
                Integer.class, properties.getTenantCode()));

        properties.setEnabled(false);
        assertThrows(RuntimeException.class, () -> provisioningService.provision(properties));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE tenant_code = ?",
                Integer.class, properties.getTenantCode()));
    }

    private void configure(String tenantCode, String ownerEmail) {
        properties.setEnabled(true);
        properties.setTenantCode(tenantCode.toUpperCase());
        properties.setLegalName("Provisioned Tenant Legal Entity");
        properties.setDisplayName("Provisioned Tenant");
        properties.setTimezone("Asia/Jakarta");
        properties.setCurrency("IDR");
        properties.setOwnerEmail(ownerEmail);
        properties.setOwnerFullName("Tenant Owner Provisioned");
        properties.setOperationRef(UUID.randomUUID().toString());
        properties.setOperatorReference("operator-console:production");
        properties.setReason("Operator approved new tenant onboarding request");
    }
}
