package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.io.response.LowStockAlertResponse;
import com.zirocraft.zirologistics.repository.InventoryLedgerRepository;
import com.zirocraft.zirologistics.repository.InventoryStockRepository;
import com.zirocraft.zirologistics.repository.LowStockAlertProjection;
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
    void getLowStockAlertsMapsAggregatedSkuWarehouseProjection() {
        InventoryStockRepository stockRepository = mock(InventoryStockRepository.class);
        InventoryLedgerRepository ledgerRepository = mock(InventoryLedgerRepository.class);
        InventoryServiceImpl service = new InventoryServiceImpl(stockRepository, ledgerRepository);
        PageRequest pageable = PageRequest.of(0, 10);

        LowStockAlertProjection projection = mock(LowStockAlertProjection.class);
        when(projection.getItemSku()).thenReturn("SKU-LOW");
        when(projection.getItemName()).thenReturn("Low Stock Item");
        when(projection.getWarehouseName()).thenReturn("Warehouse A");
        when(projection.getAvailableQuantity()).thenReturn(new BigDecimal("9.00"));
        when(projection.getMinStockLevel()).thenReturn(new BigDecimal("10.00"));
        when(stockRepository.findLowStockByWarehouse(pageable))
                .thenReturn(new PageImpl<>(List.of(projection), pageable, 1));

        Page<LowStockAlertResponse> result = service.getLowStockAlerts(pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals("SKU-LOW", result.getContent().get(0).getItemSku());
        assertEquals("Warehouse A", result.getContent().get(0).getWarehouseName());
        assertEquals(0, new BigDecimal("9.00").compareTo(
                result.getContent().get(0).getAvailableQuantity()));
        verify(stockRepository).findLowStockByWarehouse(pageable);
        verify(stockRepository, never()).findAll(pageable);
    }
}
