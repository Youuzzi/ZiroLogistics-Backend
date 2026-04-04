package com.zirocraft.zirologistics.controller;

import com.zirocraft.zirologistics.io.request.BinRequest;
import com.zirocraft.zirologistics.io.response.BinResponse;
import com.zirocraft.zirologistics.service.BinService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1.0/bins")
@RequiredArgsConstructor
public class BinController {

    private final BinService binService;

    @PostMapping
    public ResponseEntity<BinResponse> create(@Valid @RequestBody BinRequest request) {
        return new ResponseEntity<>(binService.createBin(request), HttpStatus.CREATED);
    }

    @GetMapping("/warehouse/{warehouseId}")
    public ResponseEntity<List<BinResponse>> getByWarehouse(@PathVariable String warehouseId) {
        return ResponseEntity.ok(binService.getBinsByWarehouse(warehouseId));
    }
}