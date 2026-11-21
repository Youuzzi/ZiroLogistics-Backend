package com.zirocraft.zirologistics.wms.authorization;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
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

        jdbc.update("INSERT INTO membership_roles (tenant_id, membership_id, role_id) VALUES (?, ?, ?)",
                tenant.getId(), membership.getId(), roleId);
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId, permissionId);

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

        jdbc.update("DELETE FROM membership_roles WHERE tenant_id = ? AND membership_id = ? AND role_id = ?",
                tenant.getId(), membership.getId(), roleId);
        WmsMembershipAccess afterRoleDemotion = accessService.resolve(userPublicId, membershipPublicId);
        assertTrue(afterRoleDemotion.permissionCodes().isEmpty(),
                "role demotion must remove all permissions on the next resolution");
        assertEquals(membershipPublicId, afterRoleDemotion.membershipPublicId());
    }
}
