package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.InventoryLedgerEntity;
import com.zirocraft.zirologistics.io.response.LedgerResponse;
import com.zirocraft.zirologistics.io.response.StockResponse;
import com.zirocraft.zirologistics.repository.InventoryLedgerRepository;
import com.zirocraft.zirologistics.repository.InventoryStockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl {

    private final InventoryStockRepository stockRepository;
    private final InventoryLedgerRepository ledgerRepository;

    // Eyes 1: Saldo Stok Saat Ini
    public List<StockResponse> getCurrentStocks() {
        return stockRepository.findAll().stream()
                .map(s -> StockResponse.builder()
                        .itemSku(s.getItem().getSku())
                        .itemName(s.getItem().getName())
                        .warehouseName(s.getBin().getWarehouse().getName())
                        .binCode(s.getBin().getBinCode())
                        .quantity(s.getQuantity())
                        .status(s.getStatus())
                        .build())
                .collect(Collectors.toList());
    }

    // Eyes 2: Alert Stok Tipis
    public List<StockResponse> getLowStockAlerts() {
        return stockRepository.findAll().stream()
                .filter(s -> s.getQuantity().compareTo(s.getItem().getMinStockLevel()) < 0)
                .map(s -> StockResponse.builder()
                        .itemSku(s.getItem().getSku())
                        .itemName(s.getItem().getName())
                        .warehouseName(s.getBin().getWarehouse().getName())
                        .binCode(s.getBin().getBinCode())
                        .quantity(s.getQuantity())
                        .status("LOW_STOCK")
                        .build())
                .collect(Collectors.toList());
    }

    // Eyes 3: Histori Riwayat (FIXED: Menggunakan DTO agar tidak error ByteBuddy)
    public List<LedgerResponse> getTransactionHistory() {
        return ledgerRepository.findAll().stream()
                .map(l -> LedgerResponse.builder()
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
                        .build())
                .collect(Collectors.toList());
    }
}