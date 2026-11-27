package com.zirocraft.zirologistics.wms.security.mfa.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaFactorLifecyclePort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MySqlWmsTenantMfaFactorLifecycleAdapterIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wms_tenant_mfa_factor_lifecycle_test")
            .withUsername("wms_mfa_factor_user")
            .withPassword("wms_tenant_mfa_factor_lifecycle_test");

    private static JdbcTemplate jdbc;
    private static WmsTenantMfaFactorLifecyclePort adapter;

    @BeforeAll
    static void createSchema() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target("31")
                .load()
                .migrate();
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        adapter = new MySqlWmsTenantMfaFactorLifecycleAdapter(
                jdbc, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void activatesOnlyPendingFactorAndSeedsReplayWatermarkWithEnrollmentCodeStep() {
        String user = insertUser("ACTIVE");
        UUID factor = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-27T09:00:00Z");
        long id = adapter.createPendingFactor(user, factor, "v1",
                new byte[12], new byte[36], now, now.plusSeconds(600));

        assertEquals("PENDING", status(id));
        assertTrue(adapter.activatePendingFactor(user, factor, 1234, now.plusSeconds(30)));
        assertEquals("ACTIVE", status(id));
        assertEquals(1234L, jdbc.queryForObject(
                "SELECT last_accepted_time_step FROM wms_tenant_mfa_factors WHERE id = ?",
                Long.class, id));
        assertFalse(adapter.activatePendingFactor(user, factor, 1235, now.plusSeconds(31)));
    }

    @Test
    void rejectsExpiredPendingFactorAndInactiveUser() {
        String user = insertUser("ACTIVE");
        UUID expired = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-27T09:00:00Z");
        long id = adapter.createPendingFactor(user, expired, "v1",
                new byte[12], new byte[36], now.minusSeconds(700), now.minusSeconds(100));
        assertFalse(adapter.activatePendingFactor(user, expired, 100, now));
        assertEquals("PENDING", status(id));

        String inactive = insertUser("SUSPENDED");
        assertThrows(IllegalStateException.class, () -> adapter.createPendingFactor(
                inactive, UUID.randomUUID(), "v1", new byte[12], new byte[36],
                now, now.plusSeconds(300)));
    }

    @Test
    void enforcesOneNonRevokedFactorPerUserAndErasesCiphertextOnRevocation() {
        String user = insertUser("ACTIVE");
        UUID factor = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-27T09:00:00Z");
        long id = adapter.createPendingFactor(user, factor, "v1",
                new byte[12], new byte[36], now, now.plusSeconds(300));
        assertThrows(IllegalStateException.class, () -> adapter.createPendingFactor(
                user, UUID.randomUUID(), "v1", new byte[12], new byte[36],
                now, now.plusSeconds(300)));

        assertTrue(adapter.revokeFactor(user, factor, now.plusSeconds(1)));
        assertEquals("REVOKED", status(id));
        assertNull(jdbc.queryForObject(
                "SELECT encrypted_seed FROM wms_tenant_mfa_factors WHERE id = ?",
                byte[].class, id));
        assertFalse(adapter.revokeFactor(user, factor, now.plusSeconds(2)));
    }

    @Test
    void rejectsInvalidExpiryAndMalformedCiphertextBeforePersistence() {
        String user = insertUser("ACTIVE");
        Instant now = Instant.parse("2026-11-27T09:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> adapter.createPendingFactor(
                user, UUID.randomUUID(), "v1", new byte[11], new byte[36], now, now.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> adapter.createPendingFactor(
                user, UUID.randomUUID(), "v1", new byte[12], new byte[36], now, now.plusSeconds(601)));
    }

    private static String insertUser(String status) {
        String publicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-only-hash', 'MFA Lifecycle Test User', ?)
                """, publicId, publicId + "@example.test", status);
        return publicId;
    }

    private static String status(long id) {
        return jdbc.queryForObject(
                "SELECT status FROM wms_tenant_mfa_factors WHERE id = ?",
                String.class, id);
    }
}
