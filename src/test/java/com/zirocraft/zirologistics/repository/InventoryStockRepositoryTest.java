package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.BinEntity;
import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.entity.WarehouseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
class InventoryStockRepositoryTest {

    @Autowired
    private InventoryStockRepository stockRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private BinRepository binRepository;

    @Test
    void findLowStockAggregatesAvailableQuantityPerSkuAndWarehouseBeforePagination() {
        WarehouseEntity warehouseA = saveWarehouse("WH-A", "Warehouse A");
        WarehouseEntity warehouseB = saveWarehouse("WH-B", "Warehouse B");

        BinEntity binA1 = saveBin(warehouseA, "A-01");
        BinEntity binA2 = saveBin(warehouseA, "A-02");
        BinEntity binB1 = saveBin(warehouseB, "B-01");

        ItemEntity item = itemRepository.save(ItemEntity.builder()
                .sku("SKU-SPLIT")
                .name("Split Stock Item")
                .baseUom("PCS")
                .minStockLevel(new BigDecimal("10.00"))
                .weightPerUnit(new BigDecimal("1.00"))
                .build());

        // Two bins in Warehouse A total 9, so this warehouse is low.
        saveStock(item, binA1, "4.00", "AVAILABLE");
        saveStock(item, binA2, "5.00", "AVAILABLE");
        // Warehouse B has 20 available, so it must not receive an alert.
        saveStock(item, binB1, "20.00", "AVAILABLE");
        // Reserved stock is not counted toward available replenishment stock.
        saveStock(item, binA1, "50.00", "RESERVED");

        Page<LowStockAlertProjection> result =
                stockRepository.findLowStockByWarehouse(PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        LowStockAlertProjection alert = result.getContent().get(0);
        assertEquals("SKU-SPLIT", alert.getItemSku());
        assertEquals("Warehouse A", alert.getWarehouseName());
        assertEquals(0, new BigDecimal("9.00").compareTo(alert.getAvailableQuantity()));
        assertEquals(0, new BigDecimal("10.00").compareTo(alert.getMinStockLevel()));
    }

    private WarehouseEntity saveWarehouse(String code, String name) {
        return warehouseRepository.save(WarehouseEntity.builder()
                .code(code)
                .name(name)
                .build());
    }

    private BinEntity saveBin(WarehouseEntity warehouse, String code) {
        return binRepository.save(BinEntity.builder()
                .warehouse(warehouse)
                .binCode(code)
                .maxWeightCapacity(new BigDecimal("1000.00"))
                .minWeightThreshold(BigDecimal.ZERO)
                .currentWeightOccupancy(BigDecimal.ZERO)
                .build());
    }

    private void saveStock(ItemEntity item, BinEntity bin, String quantity, String status) {
        stockRepository.save(InventoryStockEntity.builder()
                .item(item)
                .bin(bin)
                .quantity(new BigDecimal(quantity))
                .status(status)
                .build());
    }
}
