package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.*;
import com.zirocraft.zirologistics.io.request.TransferRequest;
import com.zirocraft.zirologistics.repository.*;
import com.zirocraft.zirologistics.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferServiceImpl implements TransferService {

    private final ItemRepository itemRepository;
    private final BinRepository binRepository;
    private final InventoryStockRepository stockRepository;
    private final InventoryLedgerRepository ledgerRepository;

    @Override
    @Transactional
    public void processTransfer(TransferRequest request, String executorEmail) {

        // 1. IDEMPOTENCY CHECK
        if (ledgerRepository.existsByRequestId(request.getRequestId())) {
            throw new RuntimeException("Transfer Request ID sudah diproses!");
        }

        // 2. PESSIMISTIC LOCKING (Gembok Item agar tidak ada perubahan stok lain)
        ItemEntity item = itemRepository.findBySkuForUpdate(request.getSku())
                .orElseThrow(() -> new RuntimeException("SKU tidak ditemukan!"));

        // 3. VALIDASI LOKASI ASAL & TUJUAN
        BinEntity sourceBin = binRepository.findByBinCode(request.getSourceBinCode())
                .orElseThrow(() -> new RuntimeException("Rak Asal tidak ditemukan!"));

        BinEntity destBin = binRepository.findByBinCode(request.getDestinationBinCode())
                .orElseThrow(() -> new RuntimeException("Rak Tujuan tidak ditemukan!"));

        if (sourceBin.getBinCode().equals(destBin.getBinCode())) {
            throw new RuntimeException("Asal dan Tujuan rak tidak boleh sama!");
        }

        // 4. VALIDASI STOK DI RAK ASAL
        InventoryStockEntity sourceStock = stockRepository
                .findByItemSkuAndBinBinCodeAndStatus(item.getSku(), sourceBin.getBinCode(), "AVAILABLE")
                .orElseThrow(() -> new RuntimeException("Stok tidak ditemukan di Rak Asal!"));

        if (sourceStock.getQuantity().compareTo(request.getQuantity()) < 0) {
            throw new RuntimeException("Stok di Rak Asal tidak cukup!");
        }

        // 5. VALIDASI KAPASITAS DI RAK TUJUAN
        BigDecimal transferWeight = item.getWeightPerUnit().multiply(request.getQuantity());
        BigDecimal projectedDestWeight = destBin.getCurrentWeightOccupancy().add(transferWeight);

        if (projectedDestWeight.compareTo(destBin.getMaxWeightCapacity()) > 0) {
            throw new RuntimeException("TRANSFER FAILED: Rak Tujuan (" + destBin.getBinCode() + ") Overcapacity!");
        }

        // --- EKSEKUSI PEMINDAHAN (ATOMIC PROCESS) ---

        // A. Update Rak Asal
        BigDecimal sourceBalanceBefore = sourceStock.getQuantity();
        BigDecimal sourceBalanceAfter = sourceBalanceBefore.subtract(request.getQuantity());
        sourceStock.setQuantity(sourceBalanceAfter);
        stockRepository.save(sourceStock);

        sourceBin.setCurrentWeightOccupancy(sourceBin.getCurrentWeightOccupancy().subtract(transferWeight));
        binRepository.save(sourceBin);

        // B. Update Rak Tujuan
        InventoryStockEntity destStock = stockRepository
                .findByItemSkuAndBinBinCodeAndStatus(item.getSku(), destBin.getBinCode(), "AVAILABLE")
                .orElse(null);

        BigDecimal destBalanceBefore = (destStock != null) ? destStock.getQuantity() : BigDecimal.ZERO;
        BigDecimal destBalanceAfter = destBalanceBefore.add(request.getQuantity());

        if (destStock == null) {
            destStock = InventoryStockEntity.builder()
                    .item(item).bin(destBin).quantity(request.getQuantity()).status("AVAILABLE").build();
        } else {
            destStock.setQuantity(destBalanceAfter);
        }
        stockRepository.save(destStock);

        destBin.setCurrentWeightOccupancy(destBin.getCurrentWeightOccupancy().add(transferWeight));
        binRepository.save(destBin);

        // --- PENCATATAN DUAL LEDGER (JOURNAL ENTRY) ---

        // Ledger 1: Keluar dari Rak Asal
        ledgerRepository.save(InventoryLedgerEntity.builder()
                .item(item).bin(sourceBin).transactionType("TRANSFER_OUT")
                .quantityChange(request.getQuantity().negate())
                .balanceBefore(sourceBalanceBefore).balanceAfter(sourceBalanceAfter)
                .requestId(request.getRequestId() + "_OUT") // Suffix agar unik
                .userId(executorEmail).referenceNo(request.getSku()).note("Transfer to " + destBin.getBinCode()).build());

        // Ledger 2: Masuk ke Rak Tujuan
        ledgerRepository.save(InventoryLedgerEntity.builder()
                .item(item).bin(destBin).transactionType("TRANSFER_IN")
                .quantityChange(request.getQuantity())
                .balanceBefore(destBalanceBefore).balanceAfter(destBalanceAfter)
                .requestId(request.getRequestId() + "_IN")
                .userId(executorEmail).referenceNo(request.getSku()).note("Transfer from " + sourceBin.getBinCode()).build());

        log.info("[ZIROCRAFT-TRACE] Internal Transfer Success: SKU {} from {} to {}",
                item.getSku(), sourceBin.getBinCode(), destBin.getBinCode());
    }
}