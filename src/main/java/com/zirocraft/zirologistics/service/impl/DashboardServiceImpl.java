package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.io.response.DashboardSummaryResponse;
import com.zirocraft.zirologistics.repository.BinRepository;
import com.zirocraft.zirologistics.repository.ItemRepository;
import com.zirocraft.zirologistics.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl {
    private final WarehouseRepository warehouseRepository;
    private final BinRepository binRepository;
    private final ItemRepository itemRepository;

    public DashboardSummaryResponse getSummary() {
        return DashboardSummaryResponse.builder()
                .totalWarehouses(warehouseRepository.count())
                .totalBins(binRepository.count())
                .totalItems(itemRepository.count())
                .build();
    }
}