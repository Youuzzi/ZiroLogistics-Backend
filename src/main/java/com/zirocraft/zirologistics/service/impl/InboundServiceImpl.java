package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.*;
import com.zirocraft.zirologistics.io.request.InboundRequest;
import com.zirocraft.zirologistics.repository.*;
import com.zirocraft.zirologistics.service.InboundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

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
            throw new RuntimeException("Transaksi ID sudah diproses!");
        }

        // 2. Lock Item (Pessimistic)
        ItemEntity item = itemRepository.findBySkuForUpdate(request.getSku())
                .orElseThrow(() -> new RuntimeException("SKU tidak ditemukan!"));

        BinEntity bin = binRepository.findByBinCode(request.getBinCode())
                .orElseThrow(() -> new RuntimeException("Rak tidak ditemukan!"));

        // 3. Hitung Stock
        InventoryStockEntity stock = stockRepository
                .findByItemSkuAndBinBinCodeAndStatus(item.getSku(), bin.getBinCode(), "AVAILABLE")
                .orElse(null);

        BigDecimal balanceBefore = (stock != null) ? stock.getQuantity() : BigDecimal.ZERO;
        BigDecimal balanceAfter = balanceBefore.add(request.getQuantity());

        if (stock == null) {
            stock = InventoryStockEntity.builder().item(item).bin(bin).quantity(request.getQuantity()).status("AVAILABLE").build();
        } else {
            stock.setQuantity(balanceAfter);
        }
        stockRepository.save(stock);

        // 4. Catat Ledger
        InventoryLedgerEntity ledger = InventoryLedgerEntity.builder()
                .item(item).bin(bin).transactionType("INBOUND").requestId(request.getRequestId())
                .quantityChange(request.getQuantity()).balanceBefore(balanceBefore).balanceAfter(balanceAfter)
                .userId(executorEmail).referenceNo(request.getReferenceNo()).note(request.getNote()).build();
        ledgerRepository.save(ledger);
    }
}