package com.zirocraft.zirologistics.wms.security.mfa.infrastructure.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MySqlWmsTenantMfaReplayProtectionAdapterIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wms_mfa_replay_test")
            .withUsername("wms_mfa_replay_test")
            .withPassword("wms_mfa_replay_test");

    private static JdbcTemplate jdbc;
    private static MySqlWmsTenantMfaReplayProtectionAdapter adapter;

    @BeforeAll
    static void createDisposableSchema() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE users (
                        id BIGINT NOT NULL PRIMARY KEY,
                        status VARCHAR(20) NOT NULL
                    ) ENGINE=InnoDB
                    """);
            statement.execute("""
                    CREATE TABLE wms_tenant_mfa_factors (
                        id BIGINT NOT NULL PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        factor_type VARCHAR(20) NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        last_accepted_time_step BIGINT NOT NULL DEFAULT -1,
                        locked_until DATETIME(6) NULL,
                        CONSTRAINT fk_replay_factor_user FOREIGN KEY (user_id) REFERENCES users(id)
                    ) ENGINE=InnoDB
                    """);
        }
        adapter = new MySqlWmsTenantMfaReplayProtectionAdapter(jdbc);
    }

    @Test
    void acceptsEachTimeStepOnlyOnceAndRequiresStrictlyIncreasingSteps() {
        long userId = insertUser("ACTIVE");
        long factorId = insertFactor(userId, "ACTIVE", "TOTP", -1, null);

        assertTrue(adapter.acceptTimeStep(factorId, userId, 100));
        assertFalse(adapter.acceptTimeStep(factorId, userId, 100));
        assertFalse(adapter.acceptTimeStep(factorId, userId, 99));
        assertTrue(adapter.acceptTimeStep(factorId, userId, 101));
        assertEquals(101L, jdbc.queryForObject(
                "SELECT last_accepted_time_step FROM wms_tenant_mfa_factors WHERE id = ?",
                Long.class, factorId));
    }

    @Test
    void concurrentRequestsForSameTimeStepHaveExactlyOneWinner() throws Exception {
        long userId = insertUser("ACTIVE");
        long factorId = insertFactor(userId, "ACTIVE", "TOTP", -1, null);
        int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);

        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    return new MySqlWmsTenantMfaReplayProtectionAdapter(new JdbcTemplate(
                            new DriverManagerDataSource(MYSQL.getJdbcUrl(),
                                    MYSQL.getUsername(), MYSQL.getPassword())))
                            .acceptTimeStep(factorId, userId, 500);
                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, java.util.concurrent.TimeUnit.SECONDS)) winners++;
            }
            assertEquals(1, winners);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(500L, jdbc.queryForObject(
                "SELECT last_accepted_time_step FROM wms_tenant_mfa_factors WHERE id = ?",
                Long.class, factorId));
    }

    @Test
    void rejectsPendingRevokedLockedWrongUserAndInactiveUserFactors() {
        long activeUser = insertUser("ACTIVE");
        long otherUser = insertUser("ACTIVE");
        long inactiveUser = insertUser("SUSPENDED");

        assertFalse(adapter.acceptTimeStep(
                insertFactor(activeUser, "PENDING", "TOTP", -1, null), activeUser, 1));
        assertFalse(adapter.acceptTimeStep(
                insertFactor(activeUser, "REVOKED", "TOTP", -1, null), activeUser, 1));
        assertFalse(adapter.acceptTimeStep(
                insertFactor(activeUser, "LOCKED", "TOTP", -1, null), activeUser, 1));
        assertFalse(adapter.acceptTimeStep(
                insertFactor(activeUser, "ACTIVE", "TOTP", -1, "2999-01-01 00:00:00"), activeUser, 1));
        assertFalse(adapter.acceptTimeStep(
                insertFactor(inactiveUser, "ACTIVE", "TOTP", -1, null), inactiveUser, 1));

        long activeFactor = insertFactor(activeUser, "ACTIVE", "TOTP", -1, null);
        assertFalse(adapter.acceptTimeStep(activeFactor, otherUser, 1));
    }

    @Test
    void rejectsInvalidIdentifiersAndNegativeTimeSteps() {
        assertThrows(IllegalArgumentException.class, () -> adapter.acceptTimeStep(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> adapter.acceptTimeStep(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> adapter.acceptTimeStep(1, 1, -1));
    }

    private static long insertUser(String status) {
        long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        if (id == 0) id = 1;
        jdbc.update("INSERT INTO users (id, status) VALUES (?, ?)", id, status);
        return id;
    }

    private static long insertFactor(
            long userId, String status, String factorType, long lastStep, String lockedUntil) {
        long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        if (id == 0) id = 1;
        jdbc.update("""
                INSERT INTO wms_tenant_mfa_factors
                  (id, user_id, factor_type, status, last_accepted_time_step, locked_until)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, userId, factorType, status, lastStep, lockedUntil);
        return id;
    }
}
