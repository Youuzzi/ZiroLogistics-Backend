package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.identity.domain.UserStatus;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.TenantMembershipRepository;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserEntity;
import com.zirocraft.zirologistics.wms.identity.infrastructure.persistence.UserRepository;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = WmsRuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("wms")
class WmsPlatformBootstrapMySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_platform_bootstrap_test")
            .withUsername("wms_platform_bootstrap_test")
            .withPassword("wms_platform_bootstrap_test");

    @DynamicPropertySource
    static void configureWmsDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/wms-migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private WmsPlatformBootstrapService bootstrapService;
    @Autowired private UserRepository userRepository;
    @Autowired private TenantMembershipRepository membershipRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void refusesNonEmptyDatabaseAndSerializesConcurrentBootstrap() throws Exception {
        UserEntity preexisting = new UserEntity();
        preexisting.setEmail("preexisting." + UUID.randomUUID() + "@example.test");
        preexisting.setFullName("Preexisting User");
        preexisting.setPasswordHash("not-a-production-password-hash");
        preexisting.setStatus(UserStatus.ACTIVE);
        userRepository.saveAndFlush(preexisting);

        assertThrows(IllegalStateException.class,
                () -> bootstrapService.bootstrap(command(
                        "BLOCKED", "blocked@example.test", "BootstrapPassword123")));
        assertEquals(1L, count("users"));
        assertEquals(0L, count("tenants"));

        userRepository.delete(preexisting);
        userRepository.flush();

        WmsPlatformBootstrapCommand first = command(
                "ZIROCRAFT", "bootstrap.one@example.test", "BootstrapPassword123");
        WmsPlatformBootstrapCommand second = command(
                "OTHERCO", "bootstrap.two@example.test", "BootstrapPassword456");
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<WmsPlatformBootstrapResult> firstResult = executor.submit(() -> {
                start.await();
                return bootstrapService.bootstrap(first);
            });
            Future<WmsPlatformBootstrapResult> secondResult = executor.submit(() -> {
                start.await();
                return bootstrapService.bootstrap(second);
            });

            start.countDown();

            WmsPlatformBootstrapResult winner = null;
            int successful = 0;
            int rejected = 0;
            for (Future<WmsPlatformBootstrapResult> future : List.of(firstResult, secondResult)) {
                try {
                    winner = future.get();
                    successful++;
                } catch (ExecutionException failure) {
                    assertInstanceOf(IllegalStateException.class, failure.getCause());
                    rejected++;
                }
            }

            assertEquals(1, successful);
            assertEquals(1, rejected);
            assertNotNull(winner);
        }

        assertEquals(1L, count("tenants"));
        assertEquals(1L, count("users"));
        assertEquals(1L, count("tenant_memberships"));
        assertEquals(1L, count("roles"));
        assertEquals(1L, count("membership_roles"));
        assertEquals(1L, count("role_permissions"));

        Long completed = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM wms_platform_bootstrap_state
                WHERE id = 1
                  AND completed_at IS NOT NULL
                  AND bootstrap_tenant_id IS NOT NULL
                  AND bootstrap_user_id IS NOT NULL
                """, Long.class);
        assertEquals(1L, completed);

        UserEntity initialOwner = userRepository.findAll().getFirst();
        assertEquals(UserStatus.ACTIVE, initialOwner.getStatus());
        assertNotNull(initialOwner.getEmailVerifiedAt());
        assertTrue(initialOwner.getPasswordHash().startsWith("$argon2id$"));

        TenantMembershipEntity membership = membershipRepository.findAll().getFirst();
        assertEquals(initialOwner.getId(), membership.getUserId());

        String rolePermission = jdbcTemplate.queryForObject("""
                SELECT p.permission_code
                FROM role_permissions rp
                JOIN permissions p ON p.id = rp.permission_id
                JOIN roles r ON r.id = rp.role_id
                WHERE r.id = (
                    SELECT id FROM roles WHERE role_code = 'TENANT_OWNER' LIMIT 1
                )
                """, String.class);
        assertEquals(WmsPlatformBootstrapService.INITIAL_OWNER_PERMISSION, rolePermission);

        assertThrows(IllegalStateException.class,
                () -> bootstrapService.bootstrap(command(
                        "THIRDCO", "bootstrap.three@example.test", "BootstrapPassword789")));
        assertEquals(1L, count("tenants"));
        assertEquals(1L, count("users"));
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private static WmsPlatformBootstrapCommand command(
            String tenantCode,
            String email,
            String password) {
        return new WmsPlatformBootstrapCommand(
                tenantCode,
                "Bootstrap Legal Name " + tenantCode,
                "Bootstrap " + tenantCode,
                email,
                "Initial Administrator",
                password);
    }
}
