package com.zirocraft.zirologistics.wms;

import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.*;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.*;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = WmsRuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("wms")
class WmsTenantIdentityPersistenceIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v1_identity_test")
            .withUsername("wms_identity_test")
            .withPassword("wms_identity_test");

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

    @Test
    void mapsGlobalIdentityTenantMembershipAndTenantScopedAuthorizationAssignments() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        TenantEntity tenantA = tenant("TENANT-A-" + suffix);
        TenantEntity tenantB = tenant("TENANT-B-" + suffix);
        tenantA = tenantRepository.saveAndFlush(tenantA);
        tenantB = tenantRepository.saveAndFlush(tenantB);

        UserEntity user = new UserEntity();
        user.setEmail("operator-" + suffix + "@example.test");
        user.setUsername("operator-" + suffix);
        user.setPasswordHash("test-only-password-hash");
        user.setFullName("WMS Test Operator");
        user = userRepository.saveAndFlush(user);

        TenantMembershipEntity membershipA = membership(tenantA.getId(), user.getId());
        TenantMembershipEntity membershipB = membership(tenantB.getId(), user.getId());
        membershipA = membershipRepository.saveAndFlush(membershipA);
        membershipRepository.saveAndFlush(membershipB);

        RoleEntity role = new RoleEntity();
        role.setTenantId(tenantA.getId());
        role.setRoleCode("WAREHOUSE_OPERATOR");
        role.setRoleName("Warehouse Operator");
        role.setSystemRole(false);
        role = roleRepository.saveAndFlush(role);

        PermissionEntity permission = new PermissionEntity();
        permission.setPermissionCode("inventory.balance.read");
        permission.setModule("inventory");
        permission.setDescription("Read inventory balances");
        permission = permissionRepository.saveAndFlush(permission);

        MembershipRoleEntity membershipRole = new MembershipRoleEntity();
        membershipRole.setId(new MembershipRoleId(membershipA.getId(), role.getId()));
        membershipRole.setTenantId(tenantA.getId());
        membershipRoleRepository.saveAndFlush(membershipRole);

        RolePermissionEntity rolePermission = new RolePermissionEntity();
        rolePermission.setId(new RolePermissionId(role.getId(), permission.getId()));
        rolePermissionRepository.saveAndFlush(rolePermission);

        assertEquals(2, membershipRepository.findAllByUserIdAndStatus(
                user.getId(), MembershipStatus.ACTIVE).size());
        assertTrue(membershipRepository.findByPublicIdAndTenantId(
                membershipA.getPublicId(), tenantA.getId()).isPresent());
        assertTrue(membershipRepository.findByPublicIdAndTenantId(
                membershipA.getPublicId(), tenantB.getId()).isEmpty());

        assertEquals(1, membershipRoleRepository.findAllByTenantIdAndIdMembershipId(
                tenantA.getId(), membershipA.getId()).size());
        assertEquals(1, rolePermissionRepository.findAllByIdRoleId(role.getId()).size());
        assertNotEquals(tenantA.getId(), tenantB.getId());
        assertNotNull(user.getPublicId());
        assertNotNull(membershipA.getPublicId());
    }

    private static TenantEntity tenant(String code) {
        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode(code);
        tenant.setLegalName("Ziro WMS Test Legal Entity");
        tenant.setDisplayName("Ziro WMS Test");
        return tenant;
    }

    private static TenantMembershipEntity membership(Long tenantId, Long userId) {
        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenantId);
        membership.setUserId(userId);
        membership.setStatus(MembershipStatus.ACTIVE);
        return membership;
    }
}
