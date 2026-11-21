package com.zirocraft.zirologistics.wms.authorization;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleAction;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantLifecycleService;
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
class WmsClosedTenantMembershipRevocationMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_closed_tenant_access_test")
            .withUsername("wms_closed_tenant_access_test")
            .withPassword("wms_closed_tenant_access_test");

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
    @Autowired private WmsTenantLifecycleService lifecycleService;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantMembershipRepository membershipRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void removedMembershipInvalidatesAnExistingIdentityOnTheNextAccessResolution() {
        UserEntity user = createTestUser();
        TenantEntity tenant = createTestTenant("REMOVED");
        TenantMembershipEntity membership = createTestMembership(user, tenant);
        String userPublicId = user.getPublicId();
        String membershipPublicId = membership.getPublicId();

        assertEquals(membershipPublicId,
                accessService.resolve(userPublicId, membershipPublicId).membershipPublicId());

        assertEquals(1, jdbc.update(
                "UPDATE tenant_memberships SET status = 'REMOVED' WHERE id = ?", membership.getId()));

        assertEquals("REMOVED", jdbc.queryForObject(
                "SELECT status FROM tenant_memberships WHERE id = ?", String.class, membership.getId()));
        assertThrows(WmsMembershipAccessDeniedException.class,
                () -> accessService.resolve(userPublicId, membershipPublicId));
    }

    @Test
    void cannotSwitchIntoAnotherUsersMembershipBySupplyingItsForeignTenantMembershipId() {
        UserEntity userA = createTestUser();
        UserEntity userB = createTestUser();
        TenantEntity tenantA = createTestTenant("SWITCH_A");
        TenantEntity tenantB = createTestTenant("SWITCH_B");
        TenantMembershipEntity membershipA = createTestMembership(userA, tenantA);
        TenantMembershipEntity membershipB = createTestMembership(userB, tenantB);

        assertEquals(membershipA.getPublicId(),
                accessService.resolve(userA.getPublicId(), membershipA.getPublicId()).membershipPublicId());
        assertThrows(WmsMembershipAccessDeniedException.class,
                () -> accessService.resolve(userA.getPublicId(), membershipB.getPublicId()));
    }

    private UserEntity createTestUser() {
        String suffix = UUID.randomUUID().toString();
        UserEntity user = new UserEntity();
        user.setEmail("membership-boundary-" + suffix + "@example.test");
        user.setUsername("member-" + suffix.substring(0, 8));
        user.setPasswordHash(passwordEncoder.encode("TestPassword123"));
        user.setFullName("Membership Boundary Test");
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private TenantEntity createTestTenant(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode(prefix + "-" + suffix.toUpperCase(Locale.ROOT));
        tenant.setLegalName("Membership Boundary Tenant " + suffix);
        tenant.setDisplayName("Membership Boundary " + suffix);
        tenant.setStatus(TenantStatus.ACTIVE);
        return tenantRepository.saveAndFlush(tenant);
    }

    private TenantMembershipEntity createTestMembership(UserEntity user, TenantEntity tenant) {
        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenant.getId());
        membership.setUserId(user.getId());
        membership.setStatus(MembershipStatus.ACTIVE);
        return membershipRepository.saveAndFlush(membership);
    }

    @Test
    void closedTenantInvalidatesAnExistingUserMembershipOnTheNextAccessResolution() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserEntity user = new UserEntity();
        user.setEmail("closed-tenant-" + suffix + "@example.test");
        user.setUsername("closed-" + suffix);
        user.setFullName("Closed Tenant Access Test");
        user.setPasswordHash(passwordEncoder.encode("TestPassword123"));
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.saveAndFlush(user);

        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode("CLOSED-" + suffix.toUpperCase());
        tenant.setLegalName("Closed Tenant Access Test");
        tenant.setDisplayName("Closed Tenant " + suffix);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant = tenantRepository.saveAndFlush(tenant);

        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenant.getId());
        membership.setUserId(user.getId());
        membership.setStatus(MembershipStatus.ACTIVE);
        membership = membershipRepository.saveAndFlush(membership);

        String userPublicId = user.getPublicId();
        String membershipPublicId = membership.getPublicId();
        assertEquals(membershipPublicId,
                accessService.resolve(userPublicId, membershipPublicId).membershipPublicId());

        String op = UUID.randomUUID().toString();
        lifecycleService.transition(WmsTenantLifecycleAction.SUSPEND, tenant.getPublicId(),
                "Suspend tenant before approved closure", op, "integration-test:operator", null);
        lifecycleService.transition(WmsTenantLifecycleAction.CLOSE, tenant.getPublicId(),
                "Close tenant after approved suspension", UUID.randomUUID().toString(),
                "integration-test:operator", "CLOSE:" + tenant.getTenantCode());

        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM tenants WHERE id = ?", String.class, tenant.getId()));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM tenant_memberships WHERE id = ?", String.class, membership.getId()));
        assertThrows(WmsMembershipAccessDeniedException.class,
                () -> accessService.resolve(userPublicId, membershipPublicId));
    }
}
