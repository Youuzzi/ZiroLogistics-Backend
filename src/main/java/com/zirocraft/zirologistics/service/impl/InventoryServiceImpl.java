package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.InventoryLedgerEntity;
import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import com.zirocraft.zirologistics.io.response.LedgerResponse;
import com.zirocraft.zirologistics.io.response.LowStockAlertResponse;
import com.zirocraft.zirologistics.io.response.StockResponse;
import com.zirocraft.zirologistics.repository.InventoryLedgerRepository;
import com.zirocraft.zirologistics.repository.InventoryStockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl {

    private final InventoryStockRepository stockRepository;
    private final InventoryLedgerRepository ledgerRepository;

    public Page<StockResponse> getCurrentStocks(Pageable pageable) {
        log.info("Monitoring current warehouse stocks. Page: {}", pageable.getPageNumber());
        return stockRepository.findAll(pageable).map(this::mapToStockResponse);
    }

    public Page<LedgerResponse> getTransactionHistory(Pageable pageable) {
        log.info("Accessing inventory movement logs. Page: {}", pageable.getPageNumber());
        return ledgerRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::mapToLedgerResponse);
    }

    /**
     * Returns one alert per SKU and warehouse. Quantities are aggregated across
     * all active bins in that warehouse and include AVAILABLE stock only.
     */
    public Page<LowStockAlertResponse> getLowStockAlerts(Pageable pageable) {
        return stockRepository.findLowStockByWarehouse(pageable)
                .map(row -> LowStockAlertResponse.builder()
                        .itemSku(row.getItemSku())
                        .itemName(row.getItemName())
                        .warehouseName(row.getWarehouseName())
                        .availableQuantity(row.getAvailableQuantity())
                        .minStockLevel(row.getMinStockLevel())
                        .build());
    }

    private StockResponse mapToStockResponse(InventoryStockEntity s) {
        return StockResponse.builder()
                .itemSku(s.getItem().getSku())
                .itemName(s.getItem().getName())
                .warehouseName(s.getBin().getWarehouse().getName())
                .binCode(s.getBin().getBinCode())
                .quantity(s.getQuantity())
                .status(s.getStatus())
                .build();
    }

    private LedgerResponse mapToLedgerResponse(InventoryLedgerEntity l) {
        return LedgerResponse.builder()
                .publicId(l.getPublicId())
                .itemSku(l.getItem().getSku())
                .itemName(l.getItem().getName())
                .binCode(l.getBin().getBinCode())
                .transactionType(l.getTransactionType())
                .quantityChange(l.getQuantityChange())
                .balanceBefore(l.getBalanceBefore())
                .balanceAfter(l.getBalanceAfter())
                .referenceNo(l.getReferenceNo())
                .userId(l.getUserId())
                .note(l.getNote())
                .createdAt(l.getCreatedAt())
                .build();
    }
}
