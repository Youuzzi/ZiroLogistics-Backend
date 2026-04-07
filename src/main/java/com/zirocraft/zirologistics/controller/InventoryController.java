package com.zirocraft.zirologistics.controller;

import com.zirocraft.zirologistics.io.response.LedgerResponse;
import com.zirocraft.zirologistics.io.response.StockResponse;
import com.zirocraft.zirologistics.service.impl.InventoryServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1.0/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryServiceImpl inventoryService;

    @GetMapping("/stocks")
    public ResponseEntity<List<StockResponse>> getStocks() {
        return ResponseEntity.ok(inventoryService.getCurrentStocks());
    }

    @GetMapping("/low-stock")
    public ResponseEntity<List<StockResponse>> getLowStock() {
        return ResponseEntity.ok(inventoryService.getLowStockAlerts());
    }

    @GetMapping("/ledger")
    public ResponseEntity<List<LedgerResponse>> getLedger() { // <--- Return LedgerResponse
        return ResponseEntity.ok(inventoryService.getTransactionHistory());
    }
}