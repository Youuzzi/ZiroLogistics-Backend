package com.zirocraft.zirologistics.wms.security.authentication.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.identity.application.WmsUserLookupService;
import com.zirocraft.zirologistics.wms.identity.domain.MembershipStatus;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.WmsLoginMembershipRepository;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtTokenService;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsClientAddressResolver;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsLoginThrottleDecision;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsLoginThrottlePolicy;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsLoginThrottleService;
import com.zirocraft.zirologistics.wms.security.throttling.application.WmsThrottleKeyHasher;
import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketKey;
import com.zirocraft.zirologistics.wms.security.throttling.infrastructure.persistence.MySqlWmsThrottleStorageAdapter;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantEntity;
import com.zirocraft.zirologistics.wms.tenancy.infrastructure.persistence.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = WmsRuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsCredentialAuthenticationMySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_credential_auth_test")
            .withUsername("wms_credential_auth_test")
            .withPassword("wms_credential_auth_test");

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
    private UserRepository userRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private TenantMembershipRepository membershipRepository;

    @Autowired
    private WmsLoginMembershipRepository loginMembershipRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("wmsPasswordEncoder")
    private PasswordEncoder passwordEncoder;

    private WmsThrottleKeyHasher keyHasher;
    private WmsLoginThrottleService throttleService;
    private WmsJwtTokenService tokenService;

    @BeforeEach
    void setUp() {
        keyHasher = new WmsThrottleKeyHasher(
                1, "integration-test-only-secret-32-bytes-minimum".getBytes(StandardCharsets.UTF_8));
        var storage = new MySqlWmsThrottleStorageAdapter(jdbcTemplate, transactionManager);
        var addressResolver = new WmsClientAddressResolver(List.of());
        var policy = new WmsLoginThrottlePolicy(
                Duration.ofHours(1), 20, Duration.ofHours(1), 3, Duration.ofMinutes(15));
        throttleService = new WmsLoginThrottleService(
                keyHasher, addressResolver, storage, policy, Clock.systemUTC());
        tokenService = mock(WmsJwtTokenService.class);
        when(tokenService.issueTenantSelectionToken(anyString()))
                .thenReturn("test-tenant-selection-token");
    }

    @Test
    void successfulLoginResetsPersistedAccountFailuresPreservesSourceHistoryAndReturnsActiveMemberships() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "auth-success-" + suffix + "@example.test";
        String sourceAddress = uniqueSourceAddress();

        UserEntity user = createActiveUser(email, "correct-password");
        TenantEntity tenant = createTenant(suffix, TenantStatus.ACTIVE);
        TenantMembershipEntity membership = createMembership(tenant, user, MembershipStatus.ACTIVE);

        // Two failures are persisted in MySQL but remain below the configured threshold.
        recordFailedAttempt(email, sourceAddress);
        recordFailedAttempt(email, sourceAddress);

        WmsCredentialAuthenticationService authenticationService = authenticationService();

        WmsCredentialAuthenticationResult result = authenticationService.authenticate(
                "  " + email.toUpperCase(Locale.ROOT) + "  ",
                "correct-password",
                sourceAddress,
                null);

        assertEquals("test-tenant-selection-token", result.tenantSelectionToken());
        assertEquals(1, result.memberships().size());
        assertEquals(membership.getPublicId(), result.memberships().getFirst().membershipPublicId());
        assertEquals(tenant.getPublicId(), result.memberships().getFirst().tenantPublicId());
        assertEquals(tenant.getTenantCode(), result.memberships().getFirst().tenantCode());
        assertEquals(tenant.getDisplayName(), result.memberships().getFirst().tenantDisplayName());

        WmsThrottleBucketKey accountKey = keyHasher.hashAccountEmail(email);
        Integer accountRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM wms_login_throttle_buckets
                WHERE bucket_type = 'ACCOUNT'
                  AND key_version = ?
                  AND bucket_key_hash = ?
                """, Integer.class, accountKey.keyVersion(),
                java.util.HexFormat.of().parseHex(accountKey.digestHex()));
        assertEquals(0, accountRows, "successful active-user authentication must clear account failures");

        WmsThrottleBucketKey sourceKey = keyHasher.hashSourceAddress(sourceAddress);
        Integer sourceRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM wms_login_throttle_buckets
                WHERE bucket_type = 'SOURCE'
                  AND key_version = ?
                  AND bucket_key_hash = ?
                """, Integer.class, sourceKey.keyVersion(),
                java.util.HexFormat.of().parseHex(sourceKey.digestHex()));
        assertEquals(1, sourceRows, "successful login must not erase source history");

        verify(tokenService).issueTenantSelectionToken(user.getPublicId());
    }

    @Test
    void wrongPasswordPersistsAccountFailureAndDoesNotIssueTenantSelectionToken() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "auth-failure-" + suffix + "@example.test";
        String sourceAddress = uniqueSourceAddress();
        createActiveUser(email, "correct-password");

        WmsCredentialAuthenticationService authenticationService = authenticationService();

        assertThrows(WmsCredentialAuthenticationException.class, () ->
                authenticationService.authenticate(email, "wrong-password", sourceAddress, null));

        WmsThrottleBucketKey accountKey = keyHasher.hashAccountEmail(email);
        Integer failures = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(attempt_count), 0)
                FROM wms_login_throttle_buckets
                WHERE bucket_type = 'ACCOUNT'
                  AND key_version = ?
                  AND bucket_key_hash = ?
                """, Integer.class, accountKey.keyVersion(),
                java.util.HexFormat.of().parseHex(accountKey.digestHex()));
        assertEquals(1, failures);
        verifyNoInteractions(tokenService);
    }

    private WmsCredentialAuthenticationService authenticationService() {
        return new WmsCredentialAuthenticationService(
                throttleService,
                new WmsUserLookupService(userRepository),
                passwordEncoder,
                new WmsUnknownUserPasswordVerifier(passwordEncoder),
                loginMembershipRepository,
                tokenService);
    }

    private void recordFailedAttempt(String email, String sourceAddress) {
        WmsLoginThrottleDecision decision = throttleService.beginAttempt(email, sourceAddress, null);
        assertTrue(decision.allowed());
        throttleService.recordFailedCredential(decision.attempt());
    }

    private UserEntity createActiveUser(String email, String rawPassword) {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setUsername("auth-" + UUID.randomUUID().toString().substring(0, 8));
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName("WMS Authentication Integration User");
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.saveAndFlush(user);
    }

    private TenantEntity createTenant(String suffix, TenantStatus status) {
        TenantEntity tenant = new TenantEntity();
        tenant.setTenantCode("AUTH-" + suffix.toUpperCase(Locale.ROOT));
        tenant.setLegalName("WMS Authentication Integration Tenant");
        tenant.setDisplayName("Authentication Tenant " + suffix);
        tenant.setStatus(status);
        return tenantRepository.saveAndFlush(tenant);
    }

    private TenantMembershipEntity createMembership(
            TenantEntity tenant,
            UserEntity user,
            MembershipStatus status) {
        TenantMembershipEntity membership = new TenantMembershipEntity();
        membership.setTenantId(tenant.getId());
        membership.setUserId(user.getId());
        membership.setStatus(status);
        return membershipRepository.saveAndFlush(membership);
    }

    private static String uniqueSourceAddress() {
        return "198.51.100." + (1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(254));
    }
}
