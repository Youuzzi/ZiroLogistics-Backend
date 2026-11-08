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

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlInventoryDecimalMovementIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("zirologistics_decimal_movement_test")
            .withUsername("decimal_test")
            .withPassword("decimal_test");

    @DynamicPropertySource
    static void configureMySql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("ziro.jwt.secret",
                () -> "mysql-decimal-test-secret-key-at-least-32-characters");
    }

    @Autowired private WarehouseRepository warehouseRepository;
    @Autowired private BinRepository binRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private InventoryStockRepository stockRepository;
    @Autowired private InventoryLedgerRepository ledgerRepository;
    @Autowired private InboundServiceImpl inboundService;
    @Autowired private OutboundServiceImpl outboundService;
    @Autowired private TransferServiceImpl transferService;

    private ItemEntity item;
    private BinEntity sourceBin;
    private BinEntity destinationBin;

    @BeforeEach
    void prepareInventory() {
        ledgerRepository.deleteAll();
        stockRepository.deleteAll();
        binRepository.deleteAll();
        itemRepository.deleteAll();
        warehouseRepository.deleteAll();

        WarehouseEntity warehouse = warehouseRepository.save(WarehouseEntity.builder()
                .code("WH-DECIMAL")
                .name("Decimal Precision Test Warehouse")
                .build());

        sourceBin = binRepository.save(bin(warehouse, "BIN-DECIMAL-A"));
        destinationBin = binRepository.save(bin(warehouse, "BIN-DECIMAL-B"));

        item = itemRepository.save(ItemEntity.builder()
                .sku("SKU-DECIMAL-KG")
                .name("Fractional KG Test Item")
                .baseUom("KG")
                .minStockLevel(new BigDecimal("0.001"))
                .weightPerUnit(new BigDecimal("1.000000"))
                .build());
    }

    @Test
    void inboundPersists001KgAndNineDecimalOccupancyWithoutRoundingLoss() {
        inboundService.processInbound(inbound("IN-DECIMAL-001", sourceBin), "integration-test@example.com");

        InventoryStockEntity stock = stock(sourceBin);
        BinEntity persistedBin = binRepository.findByBinCode(sourceBin.getBinCode()).orElseThrow();
        InventoryLedgerEntity ledger = ledgerRepository.findAll().getFirst();

        assertDecimal("0.001", stock.getQuantity());
        assertDecimal("0.001", ledger.getQuantityChange());
        assertDecimal("0.001", ledger.getBalanceAfter());
        assertDecimal("0.001000000", persistedBin.getCurrentWeightOccupancy());
    }

    @Test
    void outboundPersistsFractionalQuantityAndReleasesExactPhysicalOccupancy() {
        seedStock(sourceBin, "0.001", "0.001000000");

        OutboundRequest request = new OutboundRequest();
        request.setRequestId("OUT-DECIMAL-001");
        request.setSku(item.getSku());
        request.setBinCode(sourceBin.getBinCode());
        request.setQuantity(new BigDecimal("0.001"));

        outboundService.processOutbound(request, "integration-test@example.com");

        assertDecimal("0.000", stock(sourceBin).getQuantity());
        assertDecimal("0.000000000",
                binRepository.findByBinCode(sourceBin.getBinCode()).orElseThrow().getCurrentWeightOccupancy());
        assertDecimal("-0.001", ledgerRepository.findAll().getFirst().getQuantityChange());
        assertDecimal("0.000", ledgerRepository.findAll().getFirst().getBalanceAfter());
    }

    @Test
    void transferPersistsFractionalQuantityAndNineDecimalOccupancyOnBothBins() {
        seedStock(sourceBin, "0.001", "0.001000000");

        TransferRequest request = new TransferRequest();
        request.setRequestId("MOVE-DECIMAL-001");
        request.setSku(item.getSku());
        request.setSourceBinCode(sourceBin.getBinCode());
        request.setDestinationBinCode(destinationBin.getBinCode());
        request.setQuantity(new BigDecimal("0.001"));

        transferService.processTransfer(request, "integration-test@example.com");

        assertDecimal("0.000", stock(sourceBin).getQuantity());
        assertDecimal("0.001", stock(destinationBin).getQuantity());
        assertDecimal("0.000000000",
                binRepository.findByBinCode(sourceBin.getBinCode()).orElseThrow().getCurrentWeightOccupancy());
        assertDecimal("0.001000000",
                binRepository.findByBinCode(destinationBin.getBinCode()).orElseThrow().getCurrentWeightOccupancy());
        assertEquals(2, ledgerRepository.count());
        assertDecimal("-0.001", ledgerRepository.findAll().stream()
                .filter(entry -> "MOVE-DECIMAL-001_OUT".equals(entry.getRequestId()))
                .findFirst().orElseThrow().getQuantityChange());
        assertDecimal("0.001", ledgerRepository.findAll().stream()
                .filter(entry -> "MOVE-DECIMAL-001_IN".equals(entry.getRequestId()))
                .findFirst().orElseThrow().getQuantityChange());
    }

    private BinEntity bin(WarehouseEntity warehouse, String code) {
        return BinEntity.builder()
                .warehouse(warehouse)
                .binCode(code)
                .currentWeightOccupancy(BigDecimal.ZERO)
                .maxWeightCapacity(new BigDecimal("100.000000000"))
                .minWeightThreshold(BigDecimal.ZERO)
                .build();
    }

    private InboundRequest inbound(String requestId, BinEntity bin) {
        InboundRequest request = new InboundRequest();
        request.setRequestId(requestId);
        request.setSku(item.getSku());
        request.setBinCode(bin.getBinCode());
        request.setQuantity(new BigDecimal("0.001"));
        return request;
    }

    private void seedStock(BinEntity bin, String quantity, String occupancy) {
        BinEntity managedBin = binRepository.findByBinCode(bin.getBinCode()).orElseThrow();
        managedBin.setCurrentWeightOccupancy(new BigDecimal(occupancy));
        binRepository.save(managedBin);
        stockRepository.save(InventoryStockEntity.builder()
                .item(item)
                .bin(managedBin)
                .quantity(new BigDecimal(quantity))
                .status("AVAILABLE")
                .build());
    }

    private InventoryStockEntity stock(BinEntity bin) {
        return stockRepository.findByItemSkuAndBinBinCodeAndStatus(
                item.getSku(), bin.getBinCode(), "AVAILABLE").orElseThrow();
    }

    private void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "Expected " + expected + " but was " + actual);
    }
}
