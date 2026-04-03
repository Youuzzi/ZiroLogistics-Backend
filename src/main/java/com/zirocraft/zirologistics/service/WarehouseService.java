package com.zirocraft.zirologistics.service;

import com.zirocraft.zirologistics.io.request.WarehouseRequest;
import com.zirocraft.zirologistics.io.response.WarehouseResponse;
import java.util.List;

public interface WarehouseService {
    WarehouseResponse createWarehouse(WarehouseRequest request);
    List<WarehouseResponse> getAllWarehouses();
}