package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.*;
import com.zirocraft.zirologistics.exception.ApiException;
import org.springframework.http.HttpStatus;
import com.zirocraft.zirologistics.io.request.InboundRequest;
import com.zirocraft.zirologistics.repository.*;
import com.zirocraft.zirologistics.service.InboundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class InboundServiceImpl implements InboundService {

    private final ItemRepository itemRepository;
    private final BinRepository binRepository;
    private final InventoryStockRepository stockRepository;
    private final InventoryLedgerRepository ledgerRepository;

    @Override
    @Transactional
    public void processInbound(InboundRequest request, String executorEmail) {
        // 1. Idempotency Check
        if (ledgerRepository.existsByRequestId(request.getRequestId())) {
            throw new ApiException(HttpStatus.CONFLICT, "Request ID inbound sudah pernah diproses.");
        }

        // 2. Pessimistic Locking (Item & Bin)
        ItemEntity item = itemRepository.findBySkuForUpdate(request.getSku())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SKU tidak ditemukan."));

        BinEntity bin = binRepository.findByBinCodeForUpdate(request.getBinCode())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Bin tidak ditemukan."));

        // 3. Weight Capacity Validation
        BigDecimal incomingWeight = item.getWeightPerUnit().multiply(request.getQuantity());
        BigDecimal projectedWeight = bin.getCurrentWeightOccupancy().add(incomingWeight);

        if (projectedWeight.compareTo(bin.getMaxWeightCapacity()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Kapasitas bin tidak mencukupi untuk inbound ini.");
        }

        // 4. Update Bin Occupancy
        bin.setCurrentWeightOccupancy(projectedWeight.setScale(2, RoundingMode.HALF_UP));
        binRepository.save(bin);

        // 5. Update/Create Stock
        InventoryStockEntity stock = stockRepository
                .findByItemSkuAndBinBinCodeAndStatus(item.getSku(), bin.getBinCode(), "AVAILABLE")
                .orElse(InventoryStockEntity.builder()
                        .item(item)
                        .bin(bin)
                        .quantity(BigDecimal.ZERO)
                        .status("AVAILABLE")
                        .build());

        BigDecimal balanceBefore = stock.getQuantity();
        BigDecimal balanceAfter = balanceBefore.add(request.getQuantity());
        stock.setQuantity(balanceAfter);
        stockRepository.save(stock);

        // 6. Final Ledger Entry
        ledgerRepository.save(InventoryLedgerEntity.builder()
                .item(item).bin(bin).transactionType("INBOUND")
                .requestId(request.getRequestId()).quantityChange(request.getQuantity())
                .balanceBefore(balanceBefore).balanceAfter(balanceAfter)
                .userId(executorEmail).referenceNo(request.getReferenceNo())
                .note(request.getNote()).build());
    }
}