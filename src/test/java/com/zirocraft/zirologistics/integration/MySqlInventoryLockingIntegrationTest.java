package com.zirocraft.zirologistics.integration;

import com.zirocraft.zirologistics.entity.BinEntity;
import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.entity.WarehouseEntity;
import com.zirocraft.zirologistics.exception.ApiException;
import com.zirocraft.zirologistics.io.request.OutboundRequest;
import com.zirocraft.zirologistics.repository.BinRepository;
import com.zirocraft.zirologistics.repository.InventoryLedgerRepository;
import com.zirocraft.zirologistics.repository.InventoryStockRepository;
import com.zirocraft.zirologistics.repository.ItemRepository;
import com.zirocraft.zirologistics.repository.WarehouseRepository;
import com.zirocraft.zirologistics.service.impl.OutboundServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private WarehouseRepository warehouseRepository;
    @Autowired
    private BinRepository binRepository;
    @Autowired
    private ItemRepository itemRepository;
    @Autowired
    private InventoryStockRepository stockRepository;
    @Autowired
    private InventoryLedgerRepository ledgerRepository;
    @Autowired
    private OutboundServiceImpl outboundService;

    @BeforeEach
    void prepareInventory() {
        ledgerRepository.deleteAll();
        stockRepository.deleteAll();
        binRepository.deleteAll();
        itemRepository.deleteAll();
        warehouseRepository.deleteAll();

        WarehouseEntity warehouse = warehouseRepository.save(WarehouseEntity.builder()
                .code("WH-CONCURRENCY")
                .name("Concurrency Test Warehouse")
                .build());

        BinEntity bin = binRepository.save(BinEntity.builder()
                .warehouse(warehouse)
                .binCode("BIN-CONCURRENCY")
                .maxWeightCapacity(new BigDecimal("100.00"))
                .minWeightThreshold(BigDecimal.ZERO)
                .currentWeightOccupancy(new BigDecimal("10.00"))
                .build());

        ItemEntity item = itemRepository.save(ItemEntity.builder()
                .sku("SKU-CONCURRENCY")
                .name("Concurrency Test Item")
                .baseUom("PCS")
                .minStockLevel(new BigDecimal("2.00"))
                .weightPerUnit(new BigDecimal("1.00"))
                .build());

        stockRepository.save(InventoryStockEntity.builder()
                .item(item)
                .bin(bin)
                .quantity(new BigDecimal("10.00"))
                .status("AVAILABLE")
                .build());
    }

    @Test
    void concurrentOutboundRequestsCannotOversellStockOrCorruptBinOccupancy() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> processAfterBarrier(
                    request("OUT-CONCURRENT-1"), ready, start));
            var second = executor.submit(() -> processAfterBarrier(
                    request("OUT-CONCURRENT-2"), ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both requests should be ready");
            start.countDown();

            boolean firstSucceeded = first.get(15, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(15, TimeUnit.SECONDS);

            assertEquals(1, (firstSucceeded ? 1 : 0) + (secondSucceeded ? 1 : 0),
                    "Exactly one outbound of 7 units should succeed from an initial balance of 10");

            InventoryStockEntity finalStock = stockRepository
                    .findByItemSkuAndBinBinCodeAndStatus(
                            "SKU-CONCURRENCY", "BIN-CONCURRENCY", "AVAILABLE")
                    .orElseThrow();
            BinEntity finalBin = binRepository.findByBinCode("BIN-CONCURRENCY").orElseThrow();

            assertEquals(0, new BigDecimal("3.00").compareTo(finalStock.getQuantity()));
            assertEquals(0, new BigDecimal("3.00").compareTo(finalBin.getCurrentWeightOccupancy()));
            assertEquals(1, ledgerRepository.count(),
                    "Only the successful outbound should create a ledger entry");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyOnlyApplyOneOutbound() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> processAfterBarrier(
                    request("OUT-DUPLICATE-KEY"), ready, start));
            var second = executor.submit(() -> processAfterBarrier(
                    request("OUT-DUPLICATE-KEY"), ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both duplicate requests should be ready");
            start.countDown();

            boolean firstSucceeded = first.get(15, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(15, TimeUnit.SECONDS);

            assertEquals(1, (firstSucceeded ? 1 : 0) + (secondSucceeded ? 1 : 0),
                    "Only one request with the same idempotency key may be applied");

            InventoryStockEntity finalStock = stockRepository
                    .findByItemSkuAndBinBinCodeAndStatus(
                            "SKU-CONCURRENCY", "BIN-CONCURRENCY", "AVAILABLE")
                    .orElseThrow();

            assertEquals(0, new BigDecimal("3.00").compareTo(finalStock.getQuantity()));
            assertEquals(1, ledgerRepository.count(),
                    "Duplicate request must not create a second ledger entry");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private boolean processAfterBarrier(
            OutboundRequest request, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to start concurrent outbound request");
        }
        try {
            outboundService.processOutbound(request, "integration-test@example.com");
            return true;
        } catch (ApiException expectedConflict) {
            return false;
        }
    }

    private OutboundRequest request(String requestId) {
        OutboundRequest request = new OutboundRequest();
        request.setRequestId(requestId);
        request.setSku("SKU-CONCURRENCY");
        request.setBinCode("BIN-CONCURRENCY");
        request.setQuantity(new BigDecimal("7.00"));
        return request;
    }
}
