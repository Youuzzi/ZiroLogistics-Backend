package com.zirocraft.zirologistics.integration;

import com.zirocraft.zirologistics.entity.BinEntity;
import com.zirocraft.zirologistics.entity.InventoryLedgerEntity;
import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.entity.WarehouseEntity;
import com.zirocraft.zirologistics.io.request.InboundRequest;
import com.zirocraft.zirologistics.io.request.OutboundRequest;
import com.zirocraft.zirologistics.io.request.TransferRequest;
import com.zirocraft.zirologistics.repository.BinRepository;
import com.zirocraft.zirologistics.repository.InventoryLedgerRepository;
import com.zirocraft.zirologistics.repository.InventoryStockRepository;
import com.zirocraft.zirologistics.repository.ItemRepository;
import com.zirocraft.zirologistics.repository.WarehouseRepository;
import com.zirocraft.zirologistics.service.impl.InboundServiceImpl;
import com.zirocraft.zirologistics.service.impl.OutboundServiceImpl;
import com.zirocraft.zirologistics.service.impl.TransferServiceImpl;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlInventoryQuantityPrecisionIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirologistics_precision_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureMySql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL.getDriverClassName());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("ziro.jwt.secret",
                () -> "mysql-container-test-secret-key-at-least-32-characters");
    }

    @Autowired private WarehouseRepository warehouseRepository;
    @Autowired private BinRepository binRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private InventoryStockRepository stockRepository;
    @Autowired private InventoryLedgerRepository ledgerRepository;
    @Autowired private InboundServiceImpl inboundService;
    @Autowired private OutboundServiceImpl outboundService;
    @Autowired private TransferServiceImpl transferService;
    @Autowired private Validator validator;

    @BeforeEach
    void prepareInventory() {
        ledgerRepository.deleteAll();
        stockRepository.deleteAll();
        binRepository.deleteAll();
        itemRepository.deleteAll();
        warehouseRepository.deleteAll();

        WarehouseEntity warehouse = warehouseRepository.save(WarehouseEntity.builder()
                .code("WH-PRECISION")
                .name("Precision Test Warehouse")
                .build());

        BinEntity sourceBin = binRepository.save(createBin(warehouse, "BIN-PRECISION-SOURCE"));
        binRepository.save(createBin(warehouse, "BIN-PRECISION-DESTINATION"));

        ItemEntity item = itemRepository.save(ItemEntity.builder()
                .sku("SKU-PRECISION")
                .name("Precision Test Item")
                .baseUom("PCS")
                .minStockLevel(new BigDecimal("1.00"))
                .weightPerUnit(new BigDecimal("1.00"))
                .build());

        stockRepository.save(InventoryStockEntity.builder()
                .item(item)
                .bin(sourceBin)
                .quantity(new BigDecimal("10.00"))
                .status("AVAILABLE")
                .build());
    }

    @Test
    void positiveSubScaleInboundPassesValidationButDoesNotIncreasePersistedStock() {
        InboundRequest request = new InboundRequest();
        request.setRequestId("IN-PRECISION-004");
        request.setSku("SKU-PRECISION");
        request.setBinCode("BIN-PRECISION-SOURCE");
        request.setQuantity(new BigDecimal("0.004"));

        assertTrue(validator.validate(request).isEmpty(),
                "The current @Positive request validation accepts 0.004");

        inboundService.processInbound(request, "precision-test@example.com");

        InventoryStockEntity stock = findStock("BIN-PRECISION-SOURCE");
        InventoryLedgerEntity ledger = findLedger("IN-PRECISION-004");

        assertDecimal("10.00", stock.getQuantity());
        assertDecimal("0.00", ledger.getQuantityChange());
        assertDecimal("10.00", ledger.getBalanceAfter());
    }

    @Test
    void positiveSubScaleOutboundPassesValidationButDoesNotDecreasePersistedStock() {
        OutboundRequest request = new OutboundRequest();
        request.setRequestId("OUT-PRECISION-004");
        request.setSku("SKU-PRECISION");
        request.setBinCode("BIN-PRECISION-SOURCE");
        request.setQuantity(new BigDecimal("0.004"));

        assertTrue(validator.validate(request).isEmpty(),
                "The current @Positive request validation accepts 0.004");

        outboundService.processOutbound(request, "precision-test@example.com");

        InventoryStockEntity stock = findStock("BIN-PRECISION-SOURCE");
        InventoryLedgerEntity ledger = findLedger("OUT-PRECISION-004");

        assertDecimal("10.00", stock.getQuantity());
        assertDecimal("0.00", ledger.getQuantityChange());
        assertDecimal("10.00", ledger.getBalanceAfter());
    }

    @Test
    void positiveSubScaleTransferPassesValidationButCreatesZeroPersistedMovement() {
        TransferRequest request = new TransferRequest();
        request.setRequestId("TR-PRECISION-004");
        request.setSku("SKU-PRECISION");
        request.setSourceBinCode("BIN-PRECISION-SOURCE");
        request.setDestinationBinCode("BIN-PRECISION-DESTINATION");
        request.setQuantity(new BigDecimal("0.004"));

        assertTrue(validator.validate(request).isEmpty(),
                "The current @Positive request validation accepts 0.004");

        transferService.processTransfer(request, "precision-test@example.com");

        InventoryStockEntity sourceStock = findStock("BIN-PRECISION-SOURCE");
        InventoryStockEntity destinationStock = findStock("BIN-PRECISION-DESTINATION");
        List<InventoryLedgerEntity> ledgers = ledgerRepository.findAll();

        assertDecimal("10.00", sourceStock.getQuantity());
        assertDecimal("0.00", destinationStock.getQuantity());
        assertEquals(2, ledgers.size());
        assertDecimal("0.00", ledgers.stream()
                .filter(entry -> "TRANSFER_OUT".equals(entry.getTransactionType()))
                .findFirst().orElseThrow().getQuantityChange());
        assertDecimal("0.00", ledgers.stream()
                .filter(entry -> "TRANSFER_IN".equals(entry.getTransactionType()))
                .findFirst().orElseThrow().getQuantityChange());
    }

    private BinEntity createBin(WarehouseEntity warehouse, String binCode) {
        return BinEntity.builder()
                .warehouse(warehouse)
                .binCode(binCode)
                .maxWeightCapacity(new BigDecimal("100.00"))
                .minWeightThreshold(BigDecimal.ZERO)
                .currentWeightOccupancy(new BigDecimal("10.00"))
                .build();
    }

    private InventoryStockEntity findStock(String binCode) {
        return stockRepository.findByItemSkuAndBinBinCodeAndStatus(
                "SKU-PRECISION", binCode, "AVAILABLE").orElseThrow();
    }

    private InventoryLedgerEntity findLedger(String requestId) {
        return ledgerRepository.findAll().stream()
                .filter(entry -> requestId.equals(entry.getRequestId()))
                .findFirst().orElseThrow();
    }

    private void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "Expected " + expected + " but got " + actual);
    }
}
