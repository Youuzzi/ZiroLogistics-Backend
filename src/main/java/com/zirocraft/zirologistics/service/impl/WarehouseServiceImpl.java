package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.WarehouseEntity;
import com.zirocraft.zirologistics.io.request.WarehouseRequest;
import com.zirocraft.zirologistics.io.response.WarehouseResponse;
import com.zirocraft.zirologistics.repository.WarehouseRepository;
import com.zirocraft.zirologistics.service.WarehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WarehouseServiceImpl implements WarehouseService {

    private final WarehouseRepository warehouseRepository;

    @Override
    @Transactional
    public WarehouseResponse createWarehouse(WarehouseRequest request) {
        // 1. Cek duplikasi kode gudang (Business Rule)
        if (warehouseRepository.findByCode(request.getCode()).isPresent()) {
            throw new RuntimeException("Gudang dengan kode " + request.getCode() + " sudah terdaftar!");
        }

        // 2. Map Request ke Entity
        WarehouseEntity warehouse = WarehouseEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .address(request.getAddress())
                .isDeleted(false)
                .build();

        // 3. Simpan ke Database (UUID otomatis dibuat oleh @PrePersist di BaseEntity)
        WarehouseEntity savedWarehouse = warehouseRepository.save(warehouse);

        // 4. Return Response DTO
        return mapToResponse(savedWarehouse);
    }

    @Override
    public List<WarehouseResponse> getAllWarehouses() {
        return warehouseRepository.findAll().stream()
                .filter(w -> !w.isDeleted()) // Hanya ambil yang belum dihapus (Soft Delete)
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // Helper method untuk mapping Entity ke Response
    private WarehouseResponse mapToResponse(WarehouseEntity entity) {
        return WarehouseResponse.builder()
                .publicId(entity.getPublicId())
                .code(entity.getCode())
                .name(entity.getName())

                .address(entity.getAddress())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}