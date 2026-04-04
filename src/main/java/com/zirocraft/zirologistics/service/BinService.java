package com.zirocraft.zirologistics.service;

import com.zirocraft.zirologistics.io.request.BinRequest;
import com.zirocraft.zirologistics.io.response.BinResponse;
import java.util.List;

public interface BinService {
    BinResponse createBin(BinRequest request);
    List<BinResponse> getBinsByWarehouse(String warehousePublicId);
}