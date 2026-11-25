package com.zirocraft.zirologistics.wms.platform.identity.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.WmsRuntimeApplication;
import com.zirocraft.zirologistics.wms.platform.identity.domain.PlatformOperatorStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class PlatformOperatorPersistenceMySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_platform_operator_persistence_test")
            .withUsername("wms_platform_operator_test")
            .withPassword("wms_platform_operator_test");

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
    private PlatformOperatorRepository operatorRepository;

    @Test
    void persistsIndependentPlatformOperatorAndResolvesItByPublicIdAndEmail() {
        String suffix = UUID.randomUUID().toString();
        String email = "platform-" + suffix + "@example.test";

        PlatformOperatorEntity operator = new PlatformOperatorEntity();
        operator.setEmail(email);
        operator.setPasswordHash("$argon2id$test-only-placeholder");
        operator.setFullName("Platform Persistence Test");

        assertNull(operator.getId());
        assertNull(operator.getPublicId());
        assertEquals(PlatformOperatorStatus.INVITED, operator.getStatus());

        PlatformOperatorEntity saved = operatorRepository.saveAndFlush(operator);

        assertNotNull(saved.getId());
        assertNotNull(saved.getPublicId());
        assertDoesNotThrow(() -> UUID.fromString(saved.getPublicId()));
        assertEquals(email, saved.getEmail());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());

        assertEquals(saved.getId(), operatorRepository.findByPublicId(saved.getPublicId())
                .orElseThrow().getId());
        assertEquals(saved.getId(), operatorRepository.findByEmail(email)
                .orElseThrow().getId());
        assertTrue(operatorRepository.existsByPublicId(saved.getPublicId()));
        assertTrue(operatorRepository.existsByEmail(email));
        assertFalse(operatorRepository.existsByEmail("missing-" + suffix + "@example.test"));

        assertEquals(1L, operatorRepository.countByStatus(PlatformOperatorStatus.INVITED));
        assertEquals(0L, operatorRepository.countByStatus(PlatformOperatorStatus.ACTIVE));

        saved.setStatus(PlatformOperatorStatus.ACTIVE);
        operatorRepository.saveAndFlush(saved);

        assertEquals(0L, operatorRepository.countByStatus(PlatformOperatorStatus.INVITED));
        assertEquals(1L, operatorRepository.countByStatus(PlatformOperatorStatus.ACTIVE));
        assertEquals(PlatformOperatorStatus.ACTIVE,
                operatorRepository.findByPublicId(saved.getPublicId()).orElseThrow().getStatus());

        assertEquals(1, operatorRepository.recordSuccessfulAuthentication(saved.getId(), 0L));
        assertNotNull(operatorRepository.findByPublicId(saved.getPublicId())
                .orElseThrow().getLastAuthenticatedAt());
        assertEquals(0, operatorRepository.recordSuccessfulAuthentication(saved.getId(), 1L));
    }

    @Test
    void returnsEmptyForUnknownPlatformOperatorIdentifiers() {
        assertTrue(operatorRepository.findByPublicId(UUID.randomUUID().toString()).isEmpty());
        assertTrue(operatorRepository.findByEmail("unknown-" + UUID.randomUUID() + "@example.test").isEmpty());
    }
}
