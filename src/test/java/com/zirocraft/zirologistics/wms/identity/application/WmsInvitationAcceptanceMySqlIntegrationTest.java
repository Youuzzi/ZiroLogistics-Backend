package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsGeneratedIdentityActionToken;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = WmsRuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsInvitationAcceptanceMySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_invitation_acceptance_test")
            .withUsername("wms_invitation_acceptance_test")
            .withPassword("wms_invitation_acceptance_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsInvitationAcceptanceService acceptanceService;
    @Autowired private WmsIdentityActionTokenGenerator tokenGenerator;
    @Autowired private WmsIdentityActionTokenStoragePort tokenStorage;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantMembershipRepository membershipRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void newUserAcceptanceCreatesVerifiedIdentityMembershipAndConsumesTokenAtomically() {
        TenantEntity tenant = createTenant(TenantStatus.ACTIVE);
        UserEntity inviter = createUser("inviter", "Inviter Name", "InviterPassword123");
        String email = uniqueEmail("new-acceptance");
        WmsGeneratedIdentityActionToken generated = issueInvitation(null, tenant, inviter, email);

        WmsInvitationAcceptanceResult result = acceptanceService.acceptForNewUser(
                generated.rawToken(), "New WMS User", "StrongPassword123");

        assertEquals(tenant.getId(), result.tenantId());
        assertEquals(email, result.normalizedEmail());
        assertTrue(result.accountCreated());

        UserEntity created = userRepository.findByEmail(email).orElseThrow();
        assertEquals(UserStatus.ACTIVE, created.getStatus());
        assertNotNull(created.getEmailVerifiedAt());
        assertTrue(passwordEncoder.matches("StrongPassword123", created.getPasswordHash()));

        TenantMembershipEntity membership = membershipRepository
                .findByTenantIdAndUserId(tenant.getId(), created.getId()).orElseThrow();
        assertEquals(MembershipStatus.ACTIVE, membership.getStatus());
        assertNotNull(membership.getJoinedAt());

        Integer roleAssignments = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM membership_roles
                WHERE membership_id = ?
                """, Integer.class, membership.getId());
        assertEquals(0, roleAssignments, "invitation acceptance must not grant a role implicitly");

        var consumed = tokenStorage.findByDigest(
                generated.digest(), WmsIdentityActionTokenPurpose.INVITATION).orElseThrow();
        assertNotNull(consumed.consumedAt());

        assertThrows(WmsIdentityActionTokenRejectedException.class,
                () -> acceptanceService.acceptForNewUser(
                        generated.rawToken(), "New WMS User", "StrongPassword123"));
        assertEquals(1, membershipRepository.findAllByUserIdAndStatus(
                created.getId(), MembershipStatus.ACTIVE).size());
    }

    @Test
    void existingUserAcceptanceAddsMembershipWithoutChangingGlobalCredentialsOrProfile() {
        TenantEntity tenant = createTenant(TenantStatus.ACTIVE);
        UserEntity inviter = createUser("existing-inviter", "Inviter Name", "InviterPassword123");
        UserEntity existing = createUser("existing-member", "Original Full Name", "OriginalPassword123");
        String originalHash = existing.getPasswordHash();
        String emailVerifiedAtBefore = existing.getEmailVerifiedAt() == null
                ? null : existing.getEmailVerifiedAt().toString();
        WmsGeneratedIdentityActionToken generated =
                issueInvitation(existing.getId(), tenant, inviter, existing.getEmail());

        WmsInvitationAcceptanceResult result =
                acceptanceService.acceptForExistingUser(generated.rawToken(), existing.getPublicId());

        assertFalse(result.accountCreated());
        UserEntity after = userRepository.findById(existing.getId()).orElseThrow();
        assertEquals(originalHash, after.getPasswordHash());
        assertEquals("Original Full Name", after.getFullName());
        assertEquals(emailVerifiedAtBefore,
                after.getEmailVerifiedAt() == null ? null : after.getEmailVerifiedAt().toString());

        TenantMembershipEntity membership = membershipRepository
                .findByTenantIdAndUserId(tenant.getId(), existing.getId()).orElseThrow();
        assertEquals(MembershipStatus.ACTIVE, membership.getStatus());
        assertNotNull(tokenStorage.findByDigest(
                generated.digest(), WmsIdentityActionTokenPurpose.INVITATION).orElseThrow().consumedAt());
    }

    @Test
    void suspendedTenantCannotAcceptInvitationOrCreateUser() {
        TenantEntity tenant = createTenant(TenantStatus.SUSPENDED);
        UserEntity inviter = createUser("suspended-inviter", "Inviter Name", "InviterPassword123");
        String email = uniqueEmail("suspended-tenant");
        WmsGeneratedIdentityActionToken generated = issueInvitation(null, tenant, inviter, email);

        assertThrows(WmsIdentityActionTokenRejectedException.class,
                () -> acceptanceService.acceptForNewUser(
                        generated.rawToken(), "Blocked User", "StrongPassword123"));

        assertTrue(userRepository.findByEmail(email).isEmpty());
        var token = tokenStorage.findByDigest(
                generated.digest(), WmsIdentityActionTokenPurpose.INVITATION).orElseThrow();
        assertNull(token.consumedAt());
    }

    private TenantEntity createTenant(TenantStatus status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode("INV-" + suffix);
        tenant.setLegalName("Invitation Acceptance Tenant " + suffix);
        tenant.setDisplayName("Invitation Tenant " + suffix);
        tenant.setStatus(status);
        return tenantRepository.saveAndFlush(tenant);
    }

    private UserEntity createUser(String label, String fullName, String rawPassword) {
        UserEntity user = new UserEntity();
        user.setEmail(uniqueEmail(label));
        user.setFullName(fullName);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private WmsGeneratedIdentityActionToken issueInvitation(
            Long userId,
            TenantEntity tenant,
            UserEntity inviter,
            String targetEmail) {
        WmsGeneratedIdentityActionToken generated = tokenGenerator.generate();
        Instant now = Instant.now();
        new TransactionTemplate(transactionManager).execute(status -> tokenStorage.insert(
                generated.digest(),
                WmsIdentityActionTokenPurpose.INVITATION,
                userId,
                tenant.getId(),
                inviter.getId(),
                targetEmail,
                now.plusSeconds(86_400),
                now));
        return generated;
    }

    private static String uniqueEmail(String label) {
        return label + "." + UUID.randomUUID() + "@example.test";
    }
}
