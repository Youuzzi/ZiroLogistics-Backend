package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantEntity;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantRepository;
import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = WmsRuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsLoginMembershipRepositoryIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_login_membership_test")
            .withUsername("wms_login_membership_test")
            .withPassword("wms_login_membership_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantMembershipRepository tenantMembershipRepository;

    @Autowired
    private WmsLoginMembershipRepository loginMembershipRepository;

    @Test
    void returnsOnlyActiveMembershipsForActiveTenantsUsingSafePublicProjection() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UserEntity user = new UserEntity();
        user.setEmail("login-projection-" + suffix + "@example.test");
        user.setUsername("login-projection-" + suffix);
        user.setPasswordHash("test-only-placeholder-hash");
        user.setFullName("Login Projection Test User");
        user = userRepository.saveAndFlush(user);

        TenantEntity activeTenantB = tenant("TENANT-B-" + suffix, "Beta Warehouse", TenantStatus.ACTIVE);
        TenantEntity activeTenantA = tenant("TENANT-A-" + suffix, "Alpha Warehouse", TenantStatus.ACTIVE);
        TenantEntity activeTenantC = tenant("TENANT-C-" + suffix, "Gamma Warehouse", TenantStatus.ACTIVE);
        TenantEntity suspendedTenant = tenant("TENANT-S-" + suffix, "Suspended Warehouse", TenantStatus.SUSPENDED);
        activeTenantB = tenantRepository.saveAndFlush(activeTenantB);
        activeTenantA = tenantRepository.saveAndFlush(activeTenantA);
        activeTenantC = tenantRepository.saveAndFlush(activeTenantC);
        suspendedTenant = tenantRepository.saveAndFlush(suspendedTenant);

        TenantMembershipEntity activeMembershipB = membership(activeTenantB.getId(), user.getId(), MembershipStatus.ACTIVE);
        TenantMembershipEntity activeMembershipA = membership(activeTenantA.getId(), user.getId(), MembershipStatus.ACTIVE);
        TenantMembershipEntity suspendedMembership = membership(suspendedTenant.getId(), user.getId(), MembershipStatus.ACTIVE);
        TenantMembershipEntity inactiveMembership = membership(activeTenantC.getId(), user.getId(), MembershipStatus.SUSPENDED);

        activeMembershipB = tenantMembershipRepository.saveAndFlush(activeMembershipB);
        activeMembershipA = tenantMembershipRepository.saveAndFlush(activeMembershipA);
        tenantMembershipRepository.saveAndFlush(suspendedMembership);
        tenantMembershipRepository.saveAndFlush(inactiveMembership);

        String suspendedTenantPublicId = suspendedTenant.getPublicId();
        List<WmsLoginMembershipProjection> result =
                loginMembershipRepository.findSelectableMemberships(user.getId());

        assertEquals(2, result.size());
        assertEquals("Alpha Warehouse", result.get(0).getTenantDisplayName());
        assertEquals("Beta Warehouse", result.get(1).getTenantDisplayName());

        assertEquals(activeMembershipA.getPublicId(), result.get(0).getMembershipPublicId());
        assertEquals(activeTenantA.getPublicId(), result.get(0).getTenantPublicId());
        assertEquals(activeTenantA.getTenantCode(), result.get(0).getTenantCode());

        assertEquals(activeMembershipB.getPublicId(), result.get(1).getMembershipPublicId());
        assertEquals(activeTenantB.getPublicId(), result.get(1).getTenantPublicId());
        assertEquals(activeTenantB.getTenantCode(), result.get(1).getTenantCode());

        assertFalse(result.stream().anyMatch(item ->
                item.getTenantPublicId().equals(suspendedTenantPublicId)));
        assertNotNull(result.get(0).getMembershipPublicId());
    }

    @Test
    void activeAccessResolutionFailsAfterTenantOrMembershipIsSuspended() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserEntity user = new UserEntity();
        user.setEmail("access-lifecycle-" + suffix + "@example.test");
        user.setUsername("access-lifecycle-" + suffix);
        user.setPasswordHash("test-only-placeholder-hash");
        user.setFullName("Access Lifecycle Test User");
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.saveAndFlush(user);

        TenantEntity tenant = tenant("ACCESS-LIFECYCLE-" + suffix,
                "Access Lifecycle Tenant", TenantStatus.ACTIVE);
        tenant = tenantRepository.saveAndFlush(tenant);
        TenantMembershipEntity membership = membership(
                tenant.getId(), user.getId(), MembershipStatus.ACTIVE);
        membership = tenantMembershipRepository.saveAndFlush(membership);

        assertEquals(user.getPublicId(), membershipAccessService.resolve(
                user.getPublicId(), membership.getPublicId()).userPublicId());

        jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", tenant.getId());
        assertThrows(WmsMembershipAccessDeniedException.class, () ->
                membershipAccessService.resolve(user.getPublicId(), membership.getPublicId()));

        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", tenant.getId());
        jdbc.update("UPDATE tenant_memberships SET status = 'SUSPENDED' WHERE id = ?", membership.getId());
        assertThrows(WmsMembershipAccessDeniedException.class, () ->
                membershipAccessService.resolve(user.getPublicId(), membership.getPublicId()));
    }

    private static TenantEntity tenant(String code, String displayName, TenantStatus status) {
        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode(code);
        tenant.setLegalName("Ziro WMS Integration Test");
        tenant.setDisplayName(displayName);
        tenant.setStatus(status);
        return tenant;
    }

    private static TenantMembershipEntity membership(
            Long tenantId,
            Long userId,
            MembershipStatus status) {
        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenantId);
        membership.setUserId(userId);
        membership.setStatus(status);
        return membership;
    }
}
