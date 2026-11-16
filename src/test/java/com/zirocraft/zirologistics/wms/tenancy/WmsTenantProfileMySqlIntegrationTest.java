package com.zirocraft.zirologistics.wms.tenancy;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.*;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.*;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.tenancy.application.*;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsTenantProfileMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_tenant_profile_test")
            .withUsername("wms_tenant_profile_test")
            .withPassword("wms_tenant_profile_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantMembershipRepository membershipRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private MembershipRoleRepository membershipRoleRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private WmsTenantProfileService profileService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void updatesOnlyCurrentTenantProfileAndWritesTransactionalAuditEvent() {
        Fixture fixture = fixture(true);
        WmsTenantProfile before = profileService.getCurrent(fixture.principal());
        assertEquals("ORIGINAL-" + fixture.suffix(), before.displayName());
        assertEquals(0L, before.version());

        WmsTenantProfile updated = profileService.updateCurrent(
                fixture.principal(),
                new WmsTenantProfileUpdate(
                        "Updated Legal Entity " + fixture.suffix(),
                        "Updated Display " + fixture.suffix(),
                        "Asia/Makassar",
                        "USD",
                        before.version()));

        assertEquals("Updated Legal Entity " + fixture.suffix(), updated.legalName());
        assertEquals("Updated Display " + fixture.suffix(), updated.displayName());
        assertEquals("Asia/Makassar", updated.timezone());
        assertEquals("USD", updated.defaultCurrency());
        assertEquals("TENANT-" + fixture.suffix(), updated.tenantCode());
        assertEquals(1L, updated.version());

        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events
                WHERE tenant_id = ? AND event_type = 'TENANT_PROFILE_UPDATED'
                  AND subject_public_id = ?
                """, Integer.class, fixture.tenant().getId(), fixture.tenant().getPublicId()));
        String change_payload = jdbc.queryForObject("""
                SELECT CAST(change_payload AS CHAR) FROM tenant_audit_events
                WHERE tenant_id = ? AND subject_public_id = ?
                """, String.class, fixture.tenant().getId(), fixture.tenant().getPublicId());
        assertTrue(change_payload.contains("ORIGINAL-" + fixture.suffix()));
        assertTrue(change_payload.contains("Updated Display " + fixture.suffix()));

        assertThrows(WmsTenantProfileConflictException.class, () ->
                profileService.updateCurrent(fixture.principal(),
                        new WmsTenantProfileUpdate("Another Legal", "Another Display",
                                "Asia/Jakarta", "IDR", 0L)));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ?
                """, Integer.class, fixture.tenant().getId()));
    }

    @Test
    void rejectsProfileChangesWithoutCurrentPersistedPermission() {
        Fixture fixture = fixture(false);
        assertThrows(AccessDeniedException.class, () ->
                profileService.updateCurrent(fixture.principal(),
                        new WmsTenantProfileUpdate("Changed Legal", "Changed Display",
                                "Asia/Jakarta", "IDR", 0L)));
        assertEquals("ORIGINAL-" + fixture.suffix(),
                tenantRepository.findById(fixture.tenant().getId()).orElseThrow().getDisplayName());
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ?
                """, Integer.class, fixture.tenant().getId()));
    }

    @Test
    void rejectsForgedCrossTenantPrincipalAndInvalidProfileValues() {
        Fixture fixture = fixture(true);
        TenantEntity otherTenant = tenant("OTHER-" + fixture.suffix());
        otherTenant = tenantRepository.saveAndFlush(otherTenant);
        WmsAuthenticatedPrincipal forged = new WmsAuthenticatedPrincipal(
                fixture.user().getPublicId(), otherTenant.getId(), otherTenant.getPublicId(),
                fixture.membership().getId(), fixture.membership().getPublicId(),
                Set.of(WmsTenantProfileService.UPDATE_PERMISSION));

        assertThrows(WmsMembershipAccessDeniedException.class, () ->
                profileService.getCurrent(forged));
        assertThrows(IllegalArgumentException.class, () ->
                profileService.updateCurrent(fixture.principal(),
                        new WmsTenantProfileUpdate("Changed Legal", "Changed Display",
                                "Not/A-Timezone", "IDR", 0L)));
        assertEquals("ORIGINAL-" + fixture.suffix(),
                tenantRepository.findById(fixture.tenant().getId()).orElseThrow().getDisplayName());
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM tenant_audit_events WHERE tenant_id = ?
                """, Integer.class, fixture.tenant().getId()));
    }

    private Fixture fixture(boolean grantProfilePermission) {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantEntity tenant = tenant("TENANT-" + suffix);
        tenant = tenantRepository.saveAndFlush(tenant);

        UserEntity user = new UserEntity();
        user.setEmail("tenant-owner-" + suffix.toLowerCase() + "@example.test");
        user.setPasswordHash("test-only-password-hash");
        user.setFullName("Tenant Owner Test");
        user = userRepository.saveAndFlush(user);

        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenant.getId());
        membership.setUserId(user.getId());
        membership.setStatus(MembershipStatus.ACTIVE);
        membership = membershipRepository.saveAndFlush(membership);

        if (grantProfilePermission) {
            RoleEntity role = new RoleEntity();
            role.setTenantId(tenant.getId());
            role.setRoleCode("TENANT_OWNER_" + suffix);
            role.setRoleName("Tenant Owner Test");
            role.setSystemRole(false);
            role = roleRepository.saveAndFlush(role);

            PermissionEntity permission = permissionRepository.findByPermissionCode(
                    WmsTenantProfileService.UPDATE_PERMISSION).orElseThrow();
            MembershipRoleEntity membershipRole = new MembershipRoleEntity();
            membershipRole.setId(new MembershipRoleId(membership.getId(), role.getId()));
            membershipRole.setTenantId(tenant.getId());
            membershipRoleRepository.saveAndFlush(membershipRole);

            RolePermissionEntity rolePermission = new RolePermissionEntity();
            rolePermission.setId(new RolePermissionId(role.getId(), permission.getId()));
            rolePermissionRepository.saveAndFlush(rolePermission);
        }

        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                user.getPublicId(), tenant.getId(), tenant.getPublicId(),
                membership.getId(), membership.getPublicId(),
                grantProfilePermission ? Set.of(WmsTenantProfileService.UPDATE_PERMISSION) : Set.of());
        return new Fixture(suffix, tenant, user, membership, principal);
    }

    private static TenantEntity tenant(String code) {
        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode(code);
        tenant.setLegalName("Original Legal Entity");
        tenant.setDisplayName("ORIGINAL-" + code.substring(code.length() - 8));
        tenant.setStatus(TenantStatus.ACTIVE);
        return tenant;
    }

    private record Fixture(
            String suffix,
            TenantEntity tenant,
            UserEntity user,
            TenantMembershipEntity membership,
            WmsAuthenticatedPrincipal principal) {}
}
