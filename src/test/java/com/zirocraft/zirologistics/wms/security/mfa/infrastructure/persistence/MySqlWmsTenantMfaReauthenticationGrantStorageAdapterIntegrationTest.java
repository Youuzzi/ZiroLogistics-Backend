package com.zirocraft.zirologistics.wms.security.mfa.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.security.mfa.application.WmsTenantMfaReauthenticationPurpose;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MySqlWmsTenantMfaReauthenticationGrantStorageAdapterIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wms_tenant_mfa_reauth_grant_test")
            .withUsername("wms_mfa_reauth_user")
            .withPassword("wms_tenant_mfa_reauth_grant_test");

    private static JdbcTemplate jdbc;
    private static MySqlWmsTenantMfaReauthenticationGrantStorageAdapter adapter;

    @BeforeAll
    static void createSchema() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .target("32")
                .load()
                .migrate();
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        adapter = new MySqlWmsTenantMfaReauthenticationGrantStorageAdapter(jdbc);
    }

    @Test
    void consumesGrantOnceAndBindsItToExactIdentityAndPurpose() {
        Fixture fixture = fixture();
        byte[] digest = digest();
        Instant now = Instant.parse("2026-11-28T10:00:00Z");
        adapter.insert(UUID.randomUUID(), digest, fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 4L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT,
                now, now.plusSeconds(300));

        assertFalse(adapter.consume(digest, fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 4L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_REVOCATION, now.plusSeconds(1)));
        assertFalse(adapter.consume(digest, fixture.userId(), fixture.tenantId() + 1,
                fixture.membershipId(), 4L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT, now.plusSeconds(1)));
        assertFalse(adapter.consume(digest, fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 5L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT, now.plusSeconds(1)));

        assertTrue(adapter.consume(digest, fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 4L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT, now.plusSeconds(1)));
        assertFalse(adapter.consume(digest, fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 4L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT, now.plusSeconds(2)));
    }

    @Test
    void rejectsExpiredAndRevokedGrants() {
        Fixture fixture = fixture();
        Instant now = Instant.parse("2026-11-28T10:00:00Z");

        byte[] expiredDigest = digest();
        adapter.insert(UUID.randomUUID(), expiredDigest, fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 0L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT,
                now, now.plusSeconds(30));
        assertFalse(adapter.consume(expiredDigest, fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 0L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT,
                now.plusSeconds(30)));

        byte[] revokedDigest = digest();
        adapter.insert(UUID.randomUUID(), revokedDigest, fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 0L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_RECOVERY,
                now, now.plusSeconds(300));
        assertEquals(2, adapter.revokeOutstandingForUser(fixture.userId(), now.plusSeconds(1)));
        assertFalse(adapter.consume(revokedDigest, fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 0L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_RECOVERY,
                now.plusSeconds(2)));
    }

    @Test
    void concurrentConsumersHaveExactlyOneWinner() throws Exception {
        Fixture fixture = fixture();
        byte[] digest = digest();
        Instant now = Instant.parse("2026-11-28T10:00:00Z");
        adapter.insert(UUID.randomUUID(), digest, fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 2L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT,
                now, now.plusSeconds(300));

        int contenders = 8;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return adapter.consume(digest, fixture.userId(), fixture.tenantId(),
                            fixture.membershipId(), 2L,
                            WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT,
                            now.plusSeconds(1));
                }));
            }
            ready.await();
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) if (result.get()) winners++;
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsMalformedDigestAndExpiryBeyondDatabaseContract() {
        Fixture fixture = fixture();
        Instant now = Instant.parse("2026-11-28T10:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> adapter.insert(
                UUID.randomUUID(), new byte[31], fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 0L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT,
                now, now.plusSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> adapter.insert(
                UUID.randomUUID(), digest(), fixture.userId(), fixture.tenantId(),
                fixture.membershipId(), 0L, WmsTenantMfaReauthenticationPurpose.MFA_FACTOR_ENROLLMENT,
                now, now.plusSeconds(301)));
    }

    private static Fixture fixture() {
        String userPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (public_id, email, password_hash, full_name, status)
                VALUES (?, ?, 'test-only-hash', 'MFA Reauthentication Test User', 'ACTIVE')
                """, userPublicId, userPublicId + "@example.test");
        long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE public_id = ?", Long.class, userPublicId);

        String tenantPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenants
                    (public_id, tenant_code, legal_name, display_name, status, timezone, default_currency)
                VALUES (?, ?, 'MFA Reauthentication Test Legal', 'MFA Reauthentication Test',
                        'ACTIVE', 'Asia/Jakarta', 'IDR')
                """, tenantPublicId, "MFA_" + tenantPublicId.replace("-", "").substring(0, 12));
        long tenantId = jdbc.queryForObject(
                "SELECT id FROM tenants WHERE public_id = ?", Long.class, tenantPublicId);

        String membershipPublicId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tenant_memberships (public_id, tenant_id, user_id, status, joined_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                """, membershipPublicId, tenantId, userId);
        long membershipId = jdbc.queryForObject(
                "SELECT id FROM tenant_memberships WHERE public_id = ?", Long.class, membershipPublicId);
        return new Fixture(userId, tenantId, membershipId);
    }

    private static byte[] digest() {
        byte[] value = new byte[32];
        new java.security.SecureRandom().nextBytes(value);
        return value;
    }

    private record Fixture(long userId, long tenantId, long membershipId) {}
}
