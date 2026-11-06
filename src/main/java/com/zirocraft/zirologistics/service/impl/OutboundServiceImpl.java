package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.*;
import com.zirocraft.zirologistics.exception.ApiException;
import org.springframework.http.HttpStatus;
import com.zirocraft.zirologistics.io.request.OutboundRequest;
import com.zirocraft.zirologistics.repository.*;
import com.zirocraft.zirologistics.service.OutboundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class OutboundServiceImpl implements OutboundService {

    private final ItemRepository itemRepository;
    private final BinRepository binRepository;
    private final InventoryStockRepository stockRepository;
    private final InventoryLedgerRepository ledgerRepository;

    @Override
    @Transactional
    public void processOutbound(OutboundRequest request, String executorEmail) {

        // 1. IDEMPOTENCY CHECK
        if (ledgerRepository.existsByRequestId(request.getRequestId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Request ID outbound sudah pernah diproses.");
        }

        // 2. LOCK ITEM, THEN BIN (same lock order as inbound)
        ItemEntity item = itemRepository.findBySkuForUpdate(request.getSku())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SKU tidak ditemukan."));

        BinEntity bin = binRepository.findByBinCodeForUpdate(request.getBinCode())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Bin tidak ditemukan."));

        // 3. FIND AVAILABLE STOCK IN THE LOCKED BIN
        InventoryStockEntity currentStock = stockRepository
                .findByItemSkuAndBinBinCodeAndStatus(item.getSku(), bin.getBinCode(), "AVAILABLE")
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Stok tidak ditemukan pada bin yang diminta."));

        // 4. VALIDATE QUANTITY AND BIN OCCUPANCY
        if (currentStock.getQuantity().compareTo(request.getQuantity()) < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Stok tersedia tidak mencukupi untuk outbound.");
        }

        BigDecimal outgoingWeight = item.getWeightPerUnit().multiply(request.getQuantity());
        BigDecimal projectedOccupancy = bin.getCurrentWeightOccupancy().subtract(outgoingWeight);
        if (projectedOccupancy.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Data kapasitas bin tidak konsisten dengan stok. Periksa saldo okupansi bin sebelum outbound.");
        }

        // 5. UPDATE STOCK AND PHYSICAL BIN OCCUPANCY
        BigDecimal balanceBefore = currentStock.getQuantity();
        BigDecimal balanceAfter = balanceBefore.subtract(request.getQuantity());

        currentStock.setQuantity(balanceAfter);
        stockRepository.save(currentStock);

        bin.setCurrentWeightOccupancy(projectedOccupancy);
        binRepository.save(bin);

        // 6. RECORD THE INVENTORY LEDGER
        InventoryLedgerEntity ledger = InventoryLedgerEntity.builder()
                .item(item)
                .bin(bin)
                .transactionType("OUTBOUND")
                .quantityChange(request.getQuantity().negate())
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