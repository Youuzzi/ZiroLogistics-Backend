package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.io.response.DashboardSummaryResponse;
import com.zirocraft.zirologistics.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl {
    private final WarehouseRepository warehouseRepository;
    private final BinRepository binRepository;
    private final ItemRepository itemRepository;
    private final InventoryLedgerRepository ledgerRepository;

    public DashboardSummaryResponse getSummary() {
        return DashboardSummaryResponse.builder()
                .totalWarehouses(warehouseRepository.count())
                .totalBins(binRepository.count())
                .totalItems(itemRepository.count())
                // Tambahan: Logika hitung transaksi khusus hari ini (untuk dashboard)
                .totalTransactionsToday(10) // Nanti kita buat query spesifiknya
                .build();
    }
}