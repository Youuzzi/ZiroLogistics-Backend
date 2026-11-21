package com.zirocraft.zirologistics.wms.identity.infrastructure.persistence;

import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MySqlWmsIdentityActionTokenStorageAdapterIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final SecureRandom RANDOM = new SecureRandom();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirowms_v3_token_storage_test")
            .withUsername("wms_v3_storage_test")
            .withPassword("wms_v3_storage_test");

    private static JdbcTemplate jdbcTemplate;
    private static MySqlWmsIdentityActionTokenStorageAdapter adapter;
    private static TransactionTemplate transactionTemplate;

    @BeforeAll
    static void migrateDisposableDatabaseAndCreateAdapter() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/wms-migration")
                .load()
                .migrate();

        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        adapter = new MySqlWmsIdentityActionTokenStorageAdapter(jdbcTemplate);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void locksTenantAndAtomicallySupersedesOutstandingInvitation() {
        long tenantId = insertTenant();
        long inviterId = insertUser("inviter");
        String email = uniqueEmail("resend");
        byte[] firstDigest = randomDigest();
        byte[] secondDigest = randomDigest();

        long firstId = transactionTemplate.execute(status -> {
            assertEquals(Optional.of(TenantStatus.ACTIVE), adapter.lockTenantForInvitation(tenantId));
            return adapter.insert(firstDigest, WmsIdentityActionTokenPurpose.INVITATION,
                    null, tenantId, inviterId, email, NOW.plusSeconds(86_400), NOW);
        });

        long secondId = transactionTemplate.execute(status -> {
            assertEquals(Optional.of(TenantStatus.ACTIVE), adapter.lockTenantForInvitation(tenantId));
            assertEquals(1, adapter.revokeOutstandingInvitations(tenantId, email, NOW.plusSeconds(1)));
            return adapter.insert(secondDigest, WmsIdentityActionTokenPurpose.INVITATION,
                    null, tenantId, inviterId, email, NOW.plusSeconds(86_400), NOW.plusSeconds(1));
        });

        WmsIdentityActionTokenRecord first = find(firstDigest, WmsIdentityActionTokenPurpose.INVITATION);
        WmsIdentityActionTokenRecord second = find(secondDigest, WmsIdentityActionTokenPurpose.INVITATION);
        assertEquals(firstId, first.id());
        assertNotNull(first.revokedAt());
        assertEquals(secondId, second.id());
        assertNull(second.revokedAt());
        assertTrue(second.isUsableAt(NOW.plusSeconds(2)));
    }

    @Test
    void concurrentInvitationResendsLeaveExactlyOneOutstandingToken() throws Exception {
        long tenantId = insertTenant();
        long inviterId = insertUser("concurrent-inviter");
        String email = uniqueEmail("concurrent-resend");
        int workers = 2;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);

        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent invitation test start timed out");
                    }
                    return transactionTemplate.execute(status -> {
                        assertEquals(Optional.of(TenantStatus.ACTIVE),
                                adapter.lockTenantForInvitation(tenantId));
                        adapter.revokeOutstandingInvitations(tenantId, email, NOW);
                        return adapter.insert(randomDigest(), WmsIdentityActionTokenPurpose.INVITATION,
                                null, tenantId, inviterId, email, NOW.plusSeconds(86_400), NOW);
                    });
                }));
            }

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (Future<Long> future : futures) {
                assertTrue(future.get(30, java.util.concurrent.TimeUnit.SECONDS) > 0);
            }
        } finally {
            executor.shutdownNow();
        }

        Integer outstanding = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM wms_identity_action_tokens
                WHERE tenant_id = ?
                  AND target_email = ?
                  AND purpose = 'INVITATION'
                  AND consumed_at IS NULL
                  AND revoked_at IS NULL
                  AND expires_at > ?
                """, Integer.class, tenantId, email, java.sql.Timestamp.from(NOW));
        assertEquals(1, outstanding);
    }

    @Test
    void concurrentTokenConsumptionAllowsExactlyOneWinner() throws Exception {
        long userId = insertUser("consume");
        String email = uniqueEmail("consume");
        byte[] digest = randomDigest();
        long tokenId = transactionTemplate.execute(status -> adapter.insert(
                digest, WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                userId, null, null, email, NOW.plusSeconds(1_800), NOW));

        int workers = 2;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent consume test start timed out");
                    }
                    return transactionTemplate.execute(status -> {
                        Optional<WmsIdentityActionTokenRecord> token = adapter.findByDigestForUpdate(
                                digest, WmsIdentityActionTokenPurpose.PASSWORD_RESET);
                        if (token.isEmpty() || !token.get().isUsableAt(NOW)) {
                            return false;
                        }
                        return adapter.markConsumed(token.get().id(), NOW.plusSeconds(1)) == 1;
                    });
                }));
            }

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }

        WmsIdentityActionTokenRecord consumed = find(digest, WmsIdentityActionTokenPurpose.PASSWORD_RESET);
        assertEquals(tokenId, consumed.id());
        assertNotNull(consumed.consumedAt());
        assertFalse(consumed.isUsableAt(NOW.plusSeconds(2)));
    }

    @Test
    void concurrentConsumptionAllowsExactlyOneWinnerForEveryOneTimeTokenPurpose() throws Exception {
        for (WmsIdentityActionTokenPurpose purpose : WmsIdentityActionTokenPurpose.values()) {
            assertConcurrentSingleConsumptionWinner(purpose);
        }
    }

    private void assertConcurrentSingleConsumptionWinner(WmsIdentityActionTokenPurpose purpose)
            throws Exception {
        long userId = insertUser("consume-" + purpose.name().toLowerCase());
        long tenantId = insertTenant();
        String email = uniqueEmail("consume-" + purpose.name().toLowerCase());
        byte[] digest = randomDigest();
        Long tokenUserId = purpose == WmsIdentityActionTokenPurpose.INVITATION ? null : userId;
        Long tokenTenantId = purpose == WmsIdentityActionTokenPurpose.INVITATION ? tenantId : null;
        Long inviterId = purpose == WmsIdentityActionTokenPurpose.INVITATION ? userId : null;
        long tokenId = transactionTemplate.execute(status -> adapter.insert(
                digest, purpose, tokenUserId, tokenTenantId, inviterId,
                email, NOW.plusSeconds(1_800), NOW));

        int workers = 2;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<Boolean>> futures = new ArrayList<>();

        try {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent token purpose test start timed out");
                    }
                    return transactionTemplate.execute(status -> {
                        Optional<WmsIdentityActionTokenRecord> token =
                                adapter.findByDigestForUpdate(digest, purpose);
                        if (token.isEmpty() || !token.get().isUsableAt(NOW)) {
                            return false;
                        }
                        return adapter.markConsumed(token.get().id(), NOW.plusSeconds(1)) == 1;
                    });
                }));
            }

            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(1, winners, "exactly one consumer must win for " + purpose);
        } finally {
            executor.shutdownNow();
        }

        WmsIdentityActionTokenRecord consumed = find(digest, purpose);
        assertEquals(tokenId, consumed.id());
        assertNotNull(consumed.consumedAt());
        assertFalse(consumed.isUsableAt(NOW.plusSeconds(2)));
    }

    @Test
    void revocationAndConsumptionAreConditionalAndDigestArraysAreDefensive() {
        long userId = insertUser("defensive");
        String email = uniqueEmail("defensive");
        byte[] digest = randomDigest();
        long id = transactionTemplate.execute(status -> adapter.insert(
                digest, WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION,
                userId, null, null, email, NOW.plusSeconds(3_600), NOW));

        byte[] callerDigest = digest.clone();
        WmsIdentityActionTokenRecord token = find(callerDigest, WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION);
        callerDigest[0] ^= 0x7f;
        assertNotEquals(callerDigest[0], token.tokenDigest()[0]);
        assertEquals(1, adapter.markRevoked(id, NOW.plusSeconds(1)));
        assertEquals(0, adapter.markConsumed(id, NOW.plusSeconds(2)));
        assertEquals(0, adapter.markRevoked(id, NOW.plusSeconds(2)));
        assertNotNull(find(digest, WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION).revokedAt());
    }

    @Test
    void rejectsInvalidArgumentsBeforeExecutingPersistence() {
        assertThrows(IllegalArgumentException.class, () -> adapter.insert(
                new byte[31], WmsIdentityActionTokenPurpose.INVITATION,
                null, 1L, 1L, "invalid@example.test", NOW.plusSeconds(60), NOW));
        assertThrows(IllegalArgumentException.class, () -> adapter.markConsumed(0, NOW));
        assertThrows(IllegalArgumentException.class, () -> adapter.lockTenantForInvitation(0));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.revokeOutstandingInvitations(1, " not-normalized@example.test ", NOW));
    }

    private static WmsIdentityActionTokenRecord find(
            byte[] digest,
            WmsIdentityActionTokenPurpose purpose) {
        return transactionTemplate.execute(status ->
                adapter.findByDigestForUpdate(digest, purpose).orElseThrow());
    }

    private static long insertTenant() {
        KeyHolderHolder holder = new KeyHolderHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO tenants (public_id, tenant_code, legal_name, display_name, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, "TOKEN-STORAGE-" + UUID.randomUUID());
            statement.setString(3, "Token Storage Tenant");
            statement.setString(4, "Token Storage");
            return statement;
        }, holder.keyHolder);
        return holder.keyHolder.getKey().longValue();
    }

    private static long insertUser(String label) {
        KeyHolderHolder holder = new KeyHolderHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO users (public_id, email, password_hash, full_name, status)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, uniqueEmail(label));
            statement.setString(3, "$argon2id$test-only-placeholder");
            statement.setString(4, "Token Storage " + label);
            return statement;
        }, holder.keyHolder);
        return holder.keyHolder.getKey().longValue();
    }

    private static String uniqueEmail(String label) {
        return label + "." + UUID.randomUUID() + "@example.test";
    }

    private static byte[] randomDigest() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static final class KeyHolderHolder {
        private final org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
    }
}
