package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.WarehouseEntity;
import com.zirocraft.zirologistics.io.request.WarehouseRequest;
import com.zirocraft.zirologistics.io.response.WarehouseResponse;
import com.zirocraft.zirologistics.repository.WarehouseRepository;
import com.zirocraft.zirologistics.service.WarehouseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseServiceImpl implements WarehouseService {

    private final WarehouseRepository warehouseRepository;

    @Override
    @Transactional
    public WarehouseResponse createWarehouse(WarehouseRequest request) {
        if (warehouseRepository.findByCode(request.getCode()).isPresent()) {
            throw new RuntimeException("Gudang dengan kode " + request.getCode() + " sudah terdaftar!");
        }

        WarehouseEntity warehouse = WarehouseEntity.builder()
                .code(request.getCode())
                .name(request.getName())
                .address(request.getAddress())
                .isDeleted(false)
                .build();

        WarehouseEntity savedWarehouse = warehouseRepository.save(warehouse);
        log.info("[ZIROCRAFT-TRACE] New Warehouse Created: {}", savedWarehouse.getCode());

        return mapToResponse(savedWarehouse);
    }

    @Override
    public Page<WarehouseResponse> getAllWarehouses(Pageable pageable) {
        log.info("[ZIROCRAFT-TRACE] Fetching paginated warehouses, Page: {}", pageable.getPageNumber());
        return warehouseRepository.findAllByIsDeletedFalse(pageable)
                .map(this::mapToResponse);
    }

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