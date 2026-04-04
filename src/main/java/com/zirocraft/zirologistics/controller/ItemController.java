package com.zirocraft.zirologistics.controller;

import com.zirocraft.zirologistics.io.request.ItemRequest;
import com.zirocraft.zirologistics.io.response.ItemResponse;
import com.zirocraft.zirologistics.service.ItemService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1.0/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    @PostMapping
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemRequest request) {
        ItemResponse response = itemService.createItem(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<ItemResponse>> getAll() {
        return ResponseEntity.ok(itemService.getAllItems());
    }
}