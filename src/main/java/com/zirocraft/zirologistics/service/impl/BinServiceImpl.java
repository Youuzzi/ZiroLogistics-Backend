package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.BinEntity;
import com.zirocraft.zirologistics.entity.WarehouseEntity;
import com.zirocraft.zirologistics.io.request.BinRequest;
import com.zirocraft.zirologistics.io.response.BinResponse;
import com.zirocraft.zirologistics.repository.BinRepository;
import com.zirocraft.zirologistics.repository.WarehouseRepository;
import com.zirocraft.zirologistics.service.BinService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BinServiceImpl implements BinService {

    private final BinRepository binRepository;
    private final WarehouseRepository warehouseRepository;

    @Override
    @Transactional
    public BinResponse createBin(BinRequest request) {
        // 1. Cari Warehouse induknya pakai Public ID
        WarehouseEntity warehouse = warehouseRepository.findByPublicId(request.getWarehousePublicId())
                .orElseThrow(() -> new RuntimeException("Gudang tidak ditemukan!"));

        // 2. Validasi Kode Bin Unique
        if (binRepository.findByBinCode(request.getBinCode()).isPresent()) {
            throw new RuntimeException("Kode Rak " + request.getBinCode() + " sudah dipakai!");
        }

        // 3. Map & Save dengan Industrial Capacity Logic
        BinEntity bin = BinEntity.builder()
                .warehouse(warehouse)
                .zoneName(request.getZoneName())
                .rackNumber(request.getRackNumber())
                .binCode(request.getBinCode())
                // --- LOGIC BARU: DAFTARKAN KAPASITAS ---
                .maxWeightCapacity(request.getMaxWeightCapacity())
                .minWeightThreshold(request.getMinWeightThreshold() != null ? request.getMinWeightThreshold() : BigDecimal.ZERO)
                .currentWeightOccupancy(BigDecimal.ZERO) // <--- WAJIB: Mulai dari Nol, bukan NULL
                .isDeleted(false)
                .build();

        BinEntity savedBin = binRepository.save(bin);
        return mapToResponse(savedBin);
    }

    @Override
    public List<BinResponse> getBinsByWarehouse(String warehousePublicId) {
        // Industrial Standard: Filter langsung di database lebih efisien daripada stream filter
        return binRepository.findByWarehousePublicIdAndIsDeletedFalse(warehousePublicId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private BinResponse mapToResponse(BinEntity entity) {
        return BinResponse.builder()
                .publicId(entity.getPublicId())
                .warehouseName(entity.getWarehouse().getName())
                .zoneName(entity.getZoneName())
                .rackNumber(entity.getRackNumber())
                .binCode(entity.getBinCode())
                // --- LOGIC BARU: BALIKKAN DATA KAPASITAS KE UI ---
                .maxWeightCapacity(entity.getMaxWeightCapacity())
                .currentWeightOccupancy(entity.getCurrentWeightOccupancy())
                .minWeightThreshold(entity.getMinWeightThreshold())
                .build();
    }
}