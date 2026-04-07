package com.zirocraft.zirologistics.controller;

import com.zirocraft.zirologistics.io.request.WarehouseRequest;
import com.zirocraft.zirologistics.io.response.WarehouseResponse;
import com.zirocraft.zirologistics.service.WarehouseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1.0/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    @PostMapping
    public ResponseEntity<WarehouseResponse> create(@Valid @RequestBody WarehouseRequest request) {
        return new ResponseEntity<>(warehouseService.createWarehouse(request), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<Page<WarehouseResponse>> getAll(Pageable pageable) {
        return ResponseEntity.ok(warehouseService.getAllWarehouses(pageable));
    }
}