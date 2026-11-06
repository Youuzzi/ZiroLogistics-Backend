package com.zirocraft.zirologistics.repository;

import com.zirocraft.zirologistics.entity.BinEntity;
import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.entity.WarehouseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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
    void findLowStockFiltersBeforeApplyingPagination() {
        WarehouseEntity warehouse = warehouseRepository.save(WarehouseEntity.builder()
                .code("WH-LOW")
                .name("Low Stock Test Warehouse")
                .build());

        BinEntity bin = binRepository.save(BinEntity.builder()
                .warehouse(warehouse)
                .binCode("BIN-LOW")
                .maxWeightCapacity(new BigDecimal("1000.00"))
                .minWeightThreshold(new BigDecimal("0.00"))
                .currentWeightOccupancy(new BigDecimal("0.00"))
                .build());

        ItemEntity lowItem = itemRepository.save(ItemEntity.builder()
                .sku("SKU-LOW")
                .name("Low Item")
                .baseUom("PCS")
                .minStockLevel(new BigDecimal("10.00"))
                .weightPerUnit(new BigDecimal("1.00"))
                .build());

        ItemEntity healthyItem = itemRepository.save(ItemEntity.builder()
                .sku("SKU-HEALTHY")
                .name("Healthy Item")
                .baseUom("PCS")
                .minStockLevel(new BigDecimal("5.00"))
                .weightPerUnit(new BigDecimal("1.00"))
                .build());

        stockRepository.save(InventoryStockEntity.builder()
                .item(lowItem).bin(bin).quantity(new BigDecimal("2.00")).status("AVAILABLE").build());
        stockRepository.save(InventoryStockEntity.builder()
                .item(healthyItem).bin(bin).quantity(new BigDecimal("20.00")).status("AVAILABLE").build());

        Page<InventoryStockEntity> result = stockRepository.findLowStock(PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals("SKU-LOW", result.getContent().get(0).getItem().getSku());
    }
}
