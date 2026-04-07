package com.zirocraft.zirologistics.controller;

import com.zirocraft.zirologistics.io.response.LedgerResponse;
import com.zirocraft.zirologistics.io.response.StockResponse;
import com.zirocraft.zirologistics.service.impl.InventoryServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1.0/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryServiceImpl inventoryService;

    @GetMapping("/stocks")
    public ResponseEntity<Page<StockResponse>> getStocks(Pageable pageable) {
        return ResponseEntity.ok(inventoryService.getCurrentStocks(pageable));
    }

    @GetMapping("/ledger")
    public ResponseEntity<Page<LedgerResponse>> getLedger(Pageable pageable) {
        return ResponseEntity.ok(inventoryService.getTransactionHistory(pageable));
    }
}