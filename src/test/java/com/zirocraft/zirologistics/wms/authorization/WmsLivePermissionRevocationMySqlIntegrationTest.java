package com.zirocraft.zirologistics.wms.authorization;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
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
class WmsLivePermissionRevocationMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_live_permission_revocation_test")
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

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WmsMembershipAccessService accessService;

    @Test
    void rolePermissionMutationAndMembershipRoleDemotionApplyOnNextResolutionForSameIdentity() {
        String suffix = UUID.randomUUID().toString();
        String tenantPublicId = UUID.randomUUID().toString();
        String userPublicId = UUID.randomUUID().toString();
        String membershipPublicId = UUID.randomUUID().toString();
        String rolePublicId = UUID.randomUUID().toString();

        jdbc.update("""
                INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status)
                VALUES (?, ?, 'Permission Revocation Test', 'Permission Revocation Test', 'ACTIVE')
                """, tenantPublicId, "PERM-" + suffix.substring(0, 12).toUpperCase());
        Long tenantId = jdbc.queryForObject(
                "SELECT id FROM tenants WHERE public_id = ?", Long.class, tenantPublicId);

        jdbc.update("""
                INSERT INTO users (public_id, email, username, password_hash, full_name, status)
                VALUES (?, ?, ?, 'not-used-by-this-test', 'Permission Revocation User', 'ACTIVE')
                """, userPublicId, "permission-" + suffix + "@example.test", "permission-" + suffix);
        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE public_id = ?", Long.class, userPublicId);

        jdbc.update("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, membershipPublicId, tenantId, userId);
        Long membershipId = jdbc.queryForObject(
                "SELECT id FROM tenant_memberships WHERE public_id = ?", Long.class, membershipPublicId);

        jdbc.update("""
                INSERT INTO roles (public_id, tenant_id, role_code, role_name, is_system_role, status)
                VALUES (?, ?, ?, 'Revocation Test Role', 0, 'ACTIVE')
                """, rolePublicId, tenantId, "REVOCATION_" + suffix.substring(0, 12).toUpperCase());
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM roles WHERE public_id = ?", Long.class, rolePublicId);

        Long readPermissionId = jdbc.queryForObject(
                "SELECT id FROM permissions WHERE permission_code = 'WMS_ITEM_READ'", Long.class);
        Long createPermissionId = jdbc.queryForObject(
                "SELECT id FROM permissions WHERE permission_code = 'WMS_ITEM_CREATE'", Long.class);

        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId, readPermissionId);
        jdbc.update("INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId, createPermissionId);
        jdbc.update("""
                INSERT INTO membership_roles (tenant_id, membership_id, role_id)
                VALUES (?, ?, ?)
                """, tenantId, membershipId, roleId);

        // Same authenticated subject + membership pair is reused on every resolution,
        // exactly as it is for successive requests carrying the same access token.
        WmsMembershipAccess before = accessService.resolve(userPublicId, membershipPublicId);
        assertTrue(before.hasPermission("WMS_ITEM_READ"));
        assertTrue(before.hasPermission("WMS_ITEM_CREATE"));

        // Mutate the live role-permission graph in MySQL while retaining the identity.
        assertEquals(1, jdbc.update("""
                DELETE FROM role_permissions
                WHERE role_id = ? AND permission_id = ?
                """, roleId, readPermissionId));

        WmsMembershipAccess afterPermissionRevocation =
                accessService.resolve(userPublicId, membershipPublicId);
        assertFalse(afterPermissionRevocation.hasPermission("WMS_ITEM_READ"));
        assertTrue(afterPermissionRevocation.hasPermission("WMS_ITEM_CREATE"));

        // Demote the same membership by removing its role; next resolution must lose
        // every permission from that role without issuing a replacement token.
        assertEquals(1, jdbc.update("""
                DELETE FROM membership_roles WHERE tenant_id = ? AND membership_id = ? AND role_id = ?
                """, tenantId, membershipId, roleId));

        WmsMembershipAccess afterRoleDemotion = accessService.resolve(userPublicId, membershipPublicId);
        assertFalse(afterRoleDemotion.hasPermission("WMS_ITEM_READ"));
        assertFalse(afterRoleDemotion.hasPermission("WMS_ITEM_CREATE"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM membership_roles WHERE membership_id = ? AND role_id = ?",
                Integer.class, membershipId, roleId));
    }
}
