package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.*;
import com.zirocraft.zirologistics.io.request.OutboundRequest;
import com.zirocraft.zirologistics.repository.*;
import com.zirocraft.zirologistics.service.OutboundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class OutboundServiceImpl implements OutboundService { // FIX: Nama class & Interface sudah benar

    private final ItemRepository itemRepository;
    private final InventoryStockRepository stockRepository;
    private final InventoryLedgerRepository ledgerRepository;

    @Override
    @Transactional
    public void processOutbound(OutboundRequest request, String executorEmail) {

        // 1. IDEMPOTENCY CHECK
        if (ledgerRepository.existsByRequestId(request.getRequestId())) {
            throw new RuntimeException("Transaksi Outbound ini sudah pernah diproses!");
        }

        // 2. PESSIMISTIC LOCKING PADA ITEM (Gembok Database)
        ItemEntity item = itemRepository.findBySkuForUpdate(request.getSku())
                .orElseThrow(() -> new RuntimeException("SKU " + request.getSku() + " tidak ditemukan!"));

        // 3. CARI STOK DI RAK TERSEBUT (Status WAJIB AVAILABLE)
        InventoryStockEntity currentStock = stockRepository
                .findByItemSkuAndBinBinCodeAndStatus(item.getSku(), request.getBinCode(), "AVAILABLE")
                .orElseThrow(() -> new RuntimeException("Stok tidak ditemukan di Rak " + request.getBinCode()));

        // 4. VALIDASI KUANTITAS (Anti-Minus)
        if (currentStock.getQuantity().compareTo(request.getQuantity()) < 0) {
            throw new RuntimeException("Stok tidak cukup! Tersedia: " + currentStock.getQuantity());
        }

        // 5. HITUNG BALANCES (BigDecimal Precision)
        BigDecimal balanceBefore = currentStock.getQuantity();
        BigDecimal balanceAfter = balanceBefore.subtract(request.getQuantity());

        // 6. UPDATE STOK FISIK
        currentStock.setQuantity(balanceAfter);
        stockRepository.save(currentStock);

        // 7. CATAT KE LEDGER (Audit Trail / CCTV Digital)
        InventoryLedgerEntity ledger = InventoryLedgerEntity.builder()
                .item(item)
                .bin(currentStock.getBin())
                .transactionType("OUTBOUND")
                .quantityChange(request.getQuantity().negate()) // Disimpan sebagai angka negatif
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .requestId(request.getRequestId())
                .referenceNo(request.getReferenceNo())
                .userId(executorEmail)
                .note(request.getNote())
                .build();

        ledgerRepository.save(ledger);
    }
}