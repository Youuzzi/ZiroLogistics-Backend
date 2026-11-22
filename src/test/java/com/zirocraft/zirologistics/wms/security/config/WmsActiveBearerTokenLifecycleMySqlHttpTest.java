package com.zirocraft.zirologistics.wms.security.config;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.application.WmsEmailOutboxEncryptionService;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WmsRuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(WmsActiveBearerTokenLifecycleMySqlHttpTest.SecurityProbeController.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=false"
})
class WmsActiveBearerTokenLifecycleMySqlHttpTest {
    private static final String ROUTE = "/api/wms/v1/security-probe";
    private static final KeyPair TEST_RSA_KEY_PAIR = generateTestRsaKeyPair();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_token_lifecycle")
            .withUsername("wms_token_lifecycle")
            .withPassword("wms_token_lifecycle");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("wms.security.jwt.issuer", () -> "https://zirologistics.test");
        registry.add("wms.security.jwt.tenant-selection-audience", () -> "wms-tenant-selection-test");
        registry.add("wms.security.jwt.access-audience", () -> "wms-access-test");
        registry.add("wms.security.jwt.key-id", () -> "wms-test-key");
        registry.add("wms.security.jwt.public-key-pem",
                () -> toPem("PUBLIC KEY", TEST_RSA_KEY_PAIR.getPublic().getEncoded()));
        registry.add("wms.security.jwt.private-key-pem",
                () -> toPem("PRIVATE KEY", TEST_RSA_KEY_PAIR.getPrivate().getEncoded()));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantMembershipRepository membershipRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private WmsTenantLifecycleService lifecycleService;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;

    @MockBean
    private WmsEmailOutboxEncryptionService emailOutboxEncryptionService;

    @Test
    void existingBearerTokenIsDeniedImmediatelyAfterItsTenantIsClosed() throws Exception {
        UserEntity user = createUser();
        TenantEntity tenant = createTenant("CLOSED");
        TenantMembershipEntity membership = createMembership(user, tenant);
        String token = "still-cryptographically-valid-closed-tenant";
        stubAccessToken(token, user.getPublicId(), membership.getPublicId());

        performProtectedRequest(token).andExpect(status().isOk()).andExpect(content().string("ok"));

        lifecycleService.transition(WmsTenantLifecycleAction.SUSPEND, tenant.getPublicId(),
                "Prepare tenant closure in security integration test", UUID.randomUUID().toString(),
                "integration-test:operator", null);
        lifecycleService.transition(WmsTenantLifecycleAction.CLOSE, tenant.getPublicId(),
                "Close tenant while previously issued access token remains unexpired",
                UUID.randomUUID().toString(), "integration-test:operator",
                "CLOSE:" + tenant.getTenantCode());

        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM tenants WHERE id = ?", String.class, tenant.getId()));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM tenant_memberships WHERE id = ?", String.class, membership.getId()));

        performProtectedRequest(token).andExpect(status().isUnauthorized());
    }

    @Test
    void existingBearerTokenIsDeniedImmediatelyAfterItsMembershipIsRemoved() throws Exception {
        UserEntity user = createUser();
        TenantEntity tenant = createTenant("REMOVED");
        TenantMembershipEntity membership = createMembership(user, tenant);
        String token = "still-cryptographically-valid-removed-membership";
        stubAccessToken(token, user.getPublicId(), membership.getPublicId());

        performProtectedRequest(token).andExpect(status().isOk()).andExpect(content().string("ok"));

        assertEquals(1, jdbc.update(
                "UPDATE tenant_memberships SET status = 'REMOVED' WHERE id = ?", membership.getId()));
        assertEquals("REMOVED", jdbc.queryForObject(
                "SELECT status FROM tenant_memberships WHERE id = ?", String.class, membership.getId()));

        performProtectedRequest(token).andExpect(status().isUnauthorized());
    }

    @Test
    void bearerTokenCannotSelectAnotherUsersMembershipFromAnotherTenant() throws Exception {
        UserEntity userA = createUser();
        UserEntity userB = createUser();
        TenantEntity tenantA = createTenant("SWITCH_A");
        TenantEntity tenantB = createTenant("SWITCH_B");
        TenantMembershipEntity membershipA = createMembership(userA, tenantA);
        TenantMembershipEntity membershipB = createMembership(userB, tenantB);
        String token = "foreign-tenant-membership-token";
        stubAccessToken(token, userA.getPublicId(), membershipB.getPublicId());

        assertNotEquals(membershipA.getPublicId(), membershipB.getPublicId());
        performProtectedRequest(token).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions performProtectedRequest(String token)
            throws Exception {
        return mockMvc.perform(get(ROUTE).header("Authorization", "Bearer " + token));
    }

    private static KeyPair generateTestRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String toPem(String label, byte[] encodedKey) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(encodedKey);
        return "-----BEGIN " + label + "-----\n" + body
                + "\n-----END " + label + "-----";
    }

    private void stubAccessToken(String token, String userPublicId, String membershipPublicId) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .subject(userPublicId)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("token_use", "access")
                .claim("cv", 0L)
                .claim("mid", membershipPublicId)
                .build();
        when(accessJwtDecoder.decode(token)).thenReturn(jwt);
    }

    private UserEntity createUser() {
        String suffix = UUID.randomUUID().toString();
        UserEntity user = new UserEntity();
        user.setEmail("active-token-" + suffix + "@example.test");
        user.setUsername("active-" + suffix.substring(0, 8));
        user.setPasswordHash(passwordEncoder.encode("TestPassword123"));
        user.setFullName("Active Token Lifecycle Test");
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private TenantEntity createTenant(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode(prefix + "-" + suffix.toUpperCase(Locale.ROOT));
        tenant.setLegalName("Active Token Lifecycle Tenant " + suffix);
        tenant.setDisplayName("Active Token Lifecycle " + suffix);
        tenant.setStatus(TenantStatus.ACTIVE);
        return tenantRepository.saveAndFlush(tenant);
    }

    private TenantMembershipEntity createMembership(UserEntity user, TenantEntity tenant) {
        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenant.getId());
        membership.setUserId(user.getId());
        membership.setStatus(MembershipStatus.ACTIVE);
        return membershipRepository.saveAndFlush(membership);
    }

    @RestController
    static class SecurityProbeController {
        @GetMapping(ROUTE)
        String probe() {
            return "ok";
        }
    }
}
