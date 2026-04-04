package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.io.request.ItemRequest;
import com.zirocraft.zirologistics.io.response.ItemResponse;
import com.zirocraft.zirologistics.repository.ItemRepository;
import com.zirocraft.zirologistics.service.ItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    private final ItemRepository itemRepository;

    @Override
    @Transactional
    public ItemResponse createItem(ItemRequest request) {
        // 1. Gembok SKU: Tidak boleh ada SKU kembar di gudang
        if (itemRepository.findBySku(request.getSku()).isPresent()) {
            throw new RuntimeException("SKU " + request.getSku() + " sudah terdaftar!");
        }

        // 2. Mapping Request ke Entity
        ItemEntity item = ItemEntity.builder()
                .sku(request.getSku())
                .name(request.getName())
                .description(request.getDescription())
                .baseUom(request.getBaseUom().toUpperCase())
                .minStockLevel(request.getMinStockLevel())
                .isDeleted(false)
                .build();

        // 3. Simpan
        ItemEntity savedItem = itemRepository.save(item);

        return mapToResponse(savedItem);
    }

    @Override
    public List<ItemResponse> getAllItems() {
        return itemRepository.findAll().stream()
                .filter(i -> !i.isDeleted())
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private ItemResponse mapToResponse(ItemEntity entity) {
        return ItemResponse.builder()
                .publicId(entity.getPublicId())
                .sku(entity.getSku())
                .name(entity.getName())
                .description(entity.getDescription())
                .baseUom(entity.getBaseUom())
                .minStockLevel(entity.getMinStockLevel())
                .build();
    }
}