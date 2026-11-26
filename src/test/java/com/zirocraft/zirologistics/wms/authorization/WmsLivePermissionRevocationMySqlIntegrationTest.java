package com.zirocraft.zirologistics.wms.authorization;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.authorization.application.WmsRoleManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantEntity;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantRepository;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsLivePermissionRevocationMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_live_permission_test")
            .withUsername("wms_live_permission_test")
            .withPassword("wms_live_permission_test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsMembershipAccessService accessService;
    @Autowired private WmsRoleManagementService roleManagementService;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantMembershipRepository membershipRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void permissionRemovalAndRoleDemotionApplyOnNextResolutionForSameUserAndMembership() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UserEntity user = new UserEntity();
        user.setEmail("a3-live-" + suffix + "@example.test");
        user.setUsername("a3-live-" + suffix);
        user.setFullName("A3 Live Permission Test");
        user.setPasswordHash(passwordEncoder.encode("TestPassword123"));
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.saveAndFlush(user);

        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode("A3LIVE" + suffix.toUpperCase());
        tenant.setLegalName("A3 Live Permission Test");
        tenant.setDisplayName("A3 Live " + suffix);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant = tenantRepository.saveAndFlush(tenant);

        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenant.getId());
        membership.setUserId(user.getId());
        membership.setStatus(MembershipStatus.ACTIVE);
        membership = membershipRepository.saveAndFlush(membership);

        String roleCode = "A3_TEST_" + suffix.toUpperCase();
        jdbc.update("""
                INSERT INTO roles (public_id, tenant_id, role_code, role_name, is_system_role, status)
                VALUES (?, ?, ?, ?, 0, 'ACTIVE')
                """, UUID.randomUUID().toString(), tenant.getId(), roleCode, "A3 Test Role");
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE tenant_id = ? AND role_code = ?", Long.class,
                tenant.getId(), roleCode);

        String permissionCode = "WMS_A3_TEST_" + suffix.toUpperCase();
        jdbc.update("""
                INSERT INTO permissions (permission_code, module, description)
                VALUES (?, 'A3_TEST', 'Disposable integration permission')
                """, permissionCode);
        Long permissionId = jdbc.queryForObject(
                "SELECT id FROM permissions WHERE permission_code = ?", Long.class, permissionCode);
        String retainedPermissionCode = permissionCode + "_RETAINED";
        jdbc.update("""
                INSERT INTO permissions (permission_code, module, description)
                VALUES (?, 'A3_TEST', 'Permission retained after partial revocation')
                """, retainedPermissionCode);
        Long retainedPermissionId = jdbc.queryForObject(
                "SELECT id FROM permissions WHERE permission_code = ?", Long.class, retainedPermissionCode);

        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenant.getId(), membership.getId(), roleId);
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId, permissionId);
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId, retainedPermissionId);

        String userPublicId = user.getPublicId();
        String membershipPublicId = membership.getPublicId();

        WmsMembershipAccess before = accessService.resolve(userPublicId, membershipPublicId);
        assertTrue(before.permissionCodes().contains(permissionCode),
                "permission must be present before revocation");

        jdbc.update("DELETE FROM role_permissions WHERE role_id = ? AND permission_id = ?",
                roleId, permissionId);
        WmsMembershipAccess afterPermissionRevocation =
                accessService.resolve(userPublicId, membershipPublicId);
        assertFalse(afterPermissionRevocation.permissionCodes().contains(permissionCode),
                "revoked role permission must not survive in a previously authenticated identity");
        assertTrue(afterPermissionRevocation.permissionCodes().contains(retainedPermissionCode),
                "unrevoked permission must remain effective");

        jdbc.update("DELETE FROM membership_roles WHERE tenant_id = ? AND membership_id = ? AND role_id = ?",
                tenant.getId(), membership.getId(), roleId);
        WmsMembershipAccess afterRoleDemotion = accessService.resolve(userPublicId, membershipPublicId);
        assertTrue(afterRoleDemotion.permissionCodes().isEmpty(),
                "role demotion must remove all permissions on the next resolution");
        assertEquals(membershipPublicId, afterRoleDemotion.membershipPublicId());
    }
    @Test
    void aggregatesDistinctPermissionsAcrossMultiplePersistedRoles() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

        UserEntity user = new UserEntity();
        user.setEmail("a3-multi-" + suffix.toLowerCase() + "@example.test");
        user.setUsername("a3-multi-" + suffix.toLowerCase());
        user.setFullName("A3 Multi Role Test");
        user.setPasswordHash(passwordEncoder.encode("TestPassword123"));
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.saveAndFlush(user);

        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode("A3MULTI" + suffix);
        tenant.setLegalName("A3 Multi Role Test");
        tenant.setDisplayName("A3 Multi " + suffix);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant = tenantRepository.saveAndFlush(tenant);

        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenant.getId());
        membership.setUserId(user.getId());
        membership.setStatus(MembershipStatus.ACTIVE);
        membership = membershipRepository.saveAndFlush(membership);

        String firstRoleCode = "A3_MULTI_A_" + suffix;
        String secondRoleCode = "A3_MULTI_B_" + suffix;
        jdbc.update("""
                INSERT INTO roles (public_id, tenant_id, role_code, role_name, is_system_role, status)
                VALUES (?, ?, ?, 'A3 Multi Role A', 0, 'ACTIVE'),
                       (?, ?, ?, 'A3 Multi Role B', 0, 'ACTIVE')
                """,
                UUID.randomUUID().toString(), tenant.getId(), firstRoleCode,
                UUID.randomUUID().toString(), tenant.getId(), secondRoleCode);
        Long firstRoleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE tenant_id = ? AND role_code = ?", Long.class,
                tenant.getId(), firstRoleCode);
        Long secondRoleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE tenant_id = ? AND role_code = ?", Long.class,
                tenant.getId(), secondRoleCode);

        String firstPermission = "WMS_A3_MULTI_READ_" + suffix;
        String sharedPermission = "WMS_A3_MULTI_SHARED_" + suffix;
        String secondPermission = "WMS_A3_MULTI_PICK_" + suffix;
        for (String permissionCode : java.util.List.of(
                firstPermission, sharedPermission, secondPermission)) {
            jdbc.update("""
                    INSERT INTO permissions (permission_code, module, description)
                    VALUES (?, 'A3_MULTI_ROLE', 'Multi-role integration permission')
                    """, permissionCode);
        }
        Long firstPermissionId = jdbc.queryForObject(
                "SELECT id FROM permissions WHERE permission_code = ?", Long.class, firstPermission);
        Long sharedPermissionId = jdbc.queryForObject(
                "SELECT id FROM permissions WHERE permission_code = ?", Long.class, sharedPermission);
        Long secondPermissionId = jdbc.queryForObject(
                "SELECT id FROM permissions WHERE permission_code = ?", Long.class, secondPermission);

        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenant.getId(), membership.getId(), firstRoleId);
        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenant.getId(), membership.getId(), secondRoleId);
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                firstRoleId, firstPermissionId);
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                firstRoleId, sharedPermissionId);
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                secondRoleId, sharedPermissionId);
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                secondRoleId, secondPermissionId);

        WmsMembershipAccess access = accessService.resolve(user.getPublicId(), membership.getPublicId());

        assertEquals(3, access.permissionCodes().size(),
                "permissions from both active roles must be combined and overlapping permissions deduplicated");
        assertTrue(access.hasPermission(firstPermission));
        assertTrue(access.hasPermission(sharedPermission));
        assertTrue(access.hasPermission(secondPermission));
        assertEquals(tenant.getId(), access.tenantId());
        assertEquals(tenant.getPublicId(), access.tenantPublicId());
    }

    @Test
    void customRolePermissionReplacementIsTenantScopedAuditedAndRejectsPrivilegeEscalation() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        UserEntity user = new UserEntity();
        user.setEmail("a3-role-mgmt-" + suffix.toLowerCase() + "@example.test");
        user.setUsername("a3-role-mgmt-" + suffix.toLowerCase());
        user.setFullName("A3 Role Management Test");
        user.setPasswordHash(passwordEncoder.encode("TestPassword123"));
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.saveAndFlush(user);

        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode("A3ROLE" + suffix);
        tenant.setLegalName("A3 Role Management Test");
        tenant.setDisplayName("A3 Role " + suffix);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant = tenantRepository.saveAndFlush(tenant);

        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenant.getId());
        membership.setUserId(user.getId());
        membership.setStatus(MembershipStatus.ACTIVE);
        membership = membershipRepository.saveAndFlush(membership);

        String actorRoleCode = "A3_ROLE_ACTOR_" + suffix;
        String targetRoleCode = "A3_ROLE_TARGET_" + suffix;
        jdbc.update("""
                INSERT INTO roles (public_id, tenant_id, role_code, role_name, is_system_role, status)
                VALUES (?, ?, ?, 'A3 Role Manager', 0, 'ACTIVE'),
                       (?, ?, ?, 'A3 Custom Target', 0, 'ACTIVE')
                """, UUID.randomUUID().toString(), tenant.getId(), actorRoleCode,
                UUID.randomUUID().toString(), tenant.getId(), targetRoleCode);
        Long actorRoleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE tenant_id=? AND role_code=?", Long.class,
                tenant.getId(), actorRoleCode);
        String targetRolePublicId = jdbc.queryForObject(
                "SELECT public_id FROM roles WHERE tenant_id=? AND role_code=?", String.class,
                tenant.getId(), targetRoleCode);
        Long targetRoleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE tenant_id=? AND role_code=?", Long.class,
                tenant.getId(), targetRoleCode);

        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenant.getId(), membership.getId(), actorRoleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions
                WHERE permission_code IN ('WMS_ROLE_PERMISSION_MANAGE', 'WMS_ITEM_CREATE')
                """, actorRoleId);
        jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT ?, id FROM permissions WHERE permission_code='WMS_ITEM_READ'
                """, targetRoleId);

        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                user.getPublicId(), tenant.getId(), tenant.getPublicId(),
                membership.getId(), membership.getPublicId(), java.util.Set.of());

        var replaced = roleManagementService.replaceRolePermissions(
                principal, targetRolePublicId, java.util.List.of("WMS_ITEM_CREATE"), 0L);
        assertEquals(java.util.List.of("WMS_ITEM_CREATE"), replaced);
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM roles WHERE tenant_id=? AND public_id=?",
                Long.class, tenant.getId(), targetRolePublicId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp
                JOIN permissions p ON p.id=rp.permission_id
                WHERE rp.role_id=? AND p.permission_code='WMS_ITEM_CREATE'
                """, Integer.class, targetRoleId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp
                JOIN permissions p ON p.id=rp.permission_id
                WHERE rp.role_id=? AND p.permission_code='WMS_ITEM_READ'
                """, Integer.class, targetRoleId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id=? AND actor_membership_id=? AND event_type='ROLE_PERMISSIONS_REPLACED'
                  AND subject_public_id=?
                """, Integer.class, tenant.getId(), membership.getId(), targetRolePublicId));

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                roleManagementService.replaceRolePermissions(principal, targetRolePublicId,
                        java.util.List.of("WMS_INVENTORY_POST"), 1L));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM roles WHERE tenant_id=? AND public_id=?",
                Long.class, tenant.getId(), targetRolePublicId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp
                JOIN permissions p ON p.id=rp.permission_id
                WHERE rp.role_id=? AND p.permission_code='WMS_ITEM_CREATE'
                """, Integer.class, targetRoleId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id=? AND actor_membership_id=? AND event_type='ROLE_PERMISSIONS_REPLACED'
                  AND subject_public_id=?
                """, Integer.class, tenant.getId(), membership.getId(), targetRolePublicId));

        jdbc.update("""
                DELETE FROM role_permissions WHERE role_id=? AND permission_id=(
                    SELECT id FROM permissions WHERE permission_code='WMS_ITEM_CREATE'
                )
                """, actorRoleId);
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                roleManagementService.replaceRolePermissions(principal, targetRolePublicId,
                        java.util.List.of("WMS_ITEM_CREATE"), 1L));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM roles WHERE tenant_id=? AND public_id=?",
                Long.class, tenant.getId(), targetRolePublicId));
    }

}
