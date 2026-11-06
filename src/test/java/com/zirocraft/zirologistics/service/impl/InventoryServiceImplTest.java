package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.BinEntity;
import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.entity.WarehouseEntity;
import com.zirocraft.zirologistics.repository.InventoryLedgerRepository;
import com.zirocraft.zirologistics.repository.InventoryStockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class InventoryServiceImplTest {

    @Test
    void getLowStockAlertsUsesDatabaseFilteredPageAndMapsOnlyMatchingRows() {
        InventoryStockRepository stockRepository = mock(InventoryStockRepository.class);
        InventoryLedgerRepository ledgerRepository = mock(InventoryLedgerRepository.class);
        InventoryServiceImpl service = new InventoryServiceImpl(stockRepository, ledgerRepository);
        PageRequest pageable = PageRequest.of(0, 10);

        WarehouseEntity warehouse = WarehouseEntity.builder().name("Main Warehouse").build();
        BinEntity bin = BinEntity.builder().binCode("BIN-A").warehouse(warehouse).build();
        ItemEntity item = ItemEntity.builder().sku("SKU-LOW").name("Low Stock Item").build();
        InventoryStockEntity lowStock = InventoryStockEntity.builder()
                .item(item)
                .bin(bin)
                .quantity(new BigDecimal("3.00"))
                .status("AVAILABLE")
                .build();

        when(stockRepository.findLowStock(pageable))
                .thenReturn(new PageImpl<>(List.of(lowStock), pageable, 1));

        Page<com.zirocraft.zirologistics.io.response.StockResponse> result =
                service.getLowStockAlerts(pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals("SKU-LOW", result.getContent().get(0).getItemSku());
        assertEquals("BIN-A", result.getContent().get(0).getBinCode());
        verify(stockRepository).findLowStock(pageable);
        verify(stockRepository, never()).findAll(pageable);
    }
}
