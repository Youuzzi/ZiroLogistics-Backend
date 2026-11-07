package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.*;
import com.zirocraft.zirologistics.exception.ApiException;
import org.springframework.http.HttpStatus;
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
        if (request.getSourceBinCode().equals(request.getDestinationBinCode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Bin asal dan tujuan harus berbeda.");
        }

        ItemEntity item = itemRepository.findBySkuForUpdate(request.getSku())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SKU tidak ditemukan."));

        // Same-SKU transfers serialize on the item lock. Check idempotency only
        // after locking so MySQL REPEATABLE READ cannot reuse an older snapshot.
        if (hasTransferRequest(request.getRequestId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Request ID transfer sudah pernah diproses.");
        }

        String firstBinCode = request.getSourceBinCode().compareTo(request.getDestinationBinCode()) < 0
                ? request.getSourceBinCode() : request.getDestinationBinCode();
        String secondBinCode = firstBinCode.equals(request.getSourceBinCode())
                ? request.getDestinationBinCode() : request.getSourceBinCode();

        BinEntity firstLockedBin = binRepository.findByBinCodeForUpdate(firstBinCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Bin tidak ditemukan."));
        BinEntity secondLockedBin = binRepository.findByBinCodeForUpdate(secondBinCode)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Bin tidak ditemukan."));

        BinEntity sourceBin = request.getSourceBinCode().equals(firstBinCode)
                ? firstLockedBin : secondLockedBin;
        BinEntity destBin = request.getDestinationBinCode().equals(firstBinCode)
                ? firstLockedBin : secondLockedBin;

        InventoryStockEntity sourceStock = stockRepository
                .findByItemSkuAndBinBinCodeAndStatus(item.getSku(), sourceBin.getBinCode(), "AVAILABLE")
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Stok tidak ditemukan pada bin asal."));

        if (sourceStock.getQuantity().compareTo(request.getQuantity()) < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Stok pada bin asal tidak mencukupi.");
        }

        BigDecimal transferWeight = item.getWeightPerUnit().multiply(request.getQuantity());
        BigDecimal projectedSourceWeight = sourceBin.getCurrentWeightOccupancy().subtract(transferWeight);
        if (projectedSourceWeight.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Data kapasitas bin asal tidak konsisten dengan stok. Periksa saldo okupansi sebelum transfer.");
        }

        BigDecimal projectedDestWeight = destBin.getCurrentWeightOccupancy().add(transferWeight);
        if (projectedDestWeight.compareTo(destBin.getMaxWeightCapacity()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Kapasitas bin tujuan tidak mencukupi untuk transfer ini.");
        }

        BigDecimal sourceBalanceBefore = sourceStock.getQuantity();
        BigDecimal sourceBalanceAfter = sourceBalanceBefore.subtract(request.getQuantity());
        sourceStock.setQuantity(sourceBalanceAfter);
        stockRepository.save(sourceStock);

        sourceBin.setCurrentWeightOccupancy(projectedSourceWeight);
        binRepository.save(sourceBin);

        InventoryStockEntity destStock = stockRepository
                .findByItemSkuAndBinBinCodeAndStatus(item.getSku(), destBin.getBinCode(), "AVAILABLE")
                .orElse(null);

        BigDecimal destBalanceBefore = destStock != null ? destStock.getQuantity() : BigDecimal.ZERO;
        BigDecimal destBalanceAfter = destBalanceBefore.add(request.getQuantity());

        if (destStock == null) {
            destStock = InventoryStockEntity.builder()
                    .item(item).bin(destBin).quantity(request.getQuantity()).status("AVAILABLE").build();
        } else {
            destStock.setQuantity(destBalanceAfter);
        }
        stockRepository.save(destStock);

        destBin.setCurrentWeightOccupancy(projectedDestWeight);
        binRepository.save(destBin);

        ledgerRepository.save(InventoryLedgerEntity.builder()
                .item(item).bin(sourceBin).transactionType("TRANSFER_OUT")
                .quantityChange(request.getQuantity().negate())
                .balanceBefore(sourceBalanceBefore).balanceAfter(sourceBalanceAfter)
                .requestId(request.getRequestId() + "_OUT")
                .userId(executorEmail).referenceNo(request.getSku()).note("Transfer to " + destBin.getBinCode()).build());

        ledgerRepository.save(InventoryLedgerEntity.builder()
                .item(item).bin(destBin).transactionType("TRANSFER_IN")
                .quantityChange(request.getQuantity())
                .balanceBefore(destBalanceBefore).balanceAfter(destBalanceAfter)
                .requestId(request.getRequestId() + "_IN")
                .userId(executorEmail).referenceNo(request.getSku()).note("Transfer from " + sourceBin.getBinCode()).build());

        log.info("[ZIROCRAFT-TRACE] Internal Transfer Success: SKU {} from {} to {}",
                item.getSku(), sourceBin.getBinCode(), destBin.getBinCode());
    }

    private boolean hasTransferRequest(String requestId) {
        return ledgerRepository.existsByRequestId(requestId + "_OUT")
                || ledgerRepository.existsByRequestId(requestId + "_IN");
    }
}