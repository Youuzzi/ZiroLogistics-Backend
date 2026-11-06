package com.zirocraft.zirologistics.integration;

import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MySqlInventoryLockingIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirologistics_concurrency_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureMySql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("ziro.jwt.secret",
                () -> "mysql-container-test-secret-key-at-least-32-characters");
    }

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void createLockTargetItem() {
        itemRepository.deleteAll();
        itemRepository.save(ItemEntity.builder()
                .sku("SKU-LOCK-TEST")
                .name("Concurrency Lock Test Item")
                .baseUom("PCS")
                .minStockLevel(new BigDecimal("10.00"))
                .weightPerUnit(new BigDecimal("1.00"))
                .build());
    }

    @Test
    void pessimisticWriteLockBlocksSecondTransactionUntilFirstCommits() throws Exception {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CountDownLatch secondAttemptStarted = new CountDownLatch(1);
        CountDownLatch secondLockAcquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var firstTransaction = executor.submit(() -> transactionTemplate.execute(status -> {
                ItemEntity locked = itemRepository.findBySkuForUpdate("SKU-LOCK-TEST").orElseThrow();
                assertNotNull(locked.getId());
                firstLockAcquired.countDown();
                await(releaseFirstTransaction);
                return null;
            }));

            assertTrue(firstLockAcquired.await(5, TimeUnit.SECONDS),
                    "First transaction should acquire the row lock");

            var secondTransaction = executor.submit(() -> transactionTemplate.execute(status -> {
                secondAttemptStarted.countDown();
                ItemEntity locked = itemRepository.findBySkuForUpdate("SKU-LOCK-TEST").orElseThrow();
                assertNotNull(locked.getId());
                secondLockAcquired.countDown();
                return null;
            }));

            assertTrue(secondAttemptStarted.await(5, TimeUnit.SECONDS),
                    "Second transaction should attempt to acquire the same row");
            assertFalse(secondLockAcquired.await(300, TimeUnit.MILLISECONDS),
                    "Second transaction must wait while the first transaction holds the lock");

            releaseFirstTransaction.countDown();
            firstTransaction.get(10, TimeUnit.SECONDS);
            secondTransaction.get(10, TimeUnit.SECONDS);

            assertTrue(secondLockAcquired.await(1, TimeUnit.SECONDS),
                    "Second transaction should acquire the lock after the first commits");
        } finally {
            releaseFirstTransaction.countDown();
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the first transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the test latch", exception);
        }
    }
}
