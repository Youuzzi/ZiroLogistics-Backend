package com.zirocraft.zirologistics.service;

import com.zirocraft.zirologistics.io.request.WarehouseRequest;
import com.zirocraft.zirologistics.io.response.WarehouseResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface WarehouseService {
    WarehouseResponse createWarehouse(WarehouseRequest request);

    // Industrial Standard: Wajib pakai Pageable
    Page<WarehouseResponse> getAllWarehouses(Pageable pageable);
}