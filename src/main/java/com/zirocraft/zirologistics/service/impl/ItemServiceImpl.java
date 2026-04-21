package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.io.request.ItemRequest;
import com.zirocraft.zirologistics.io.response.ItemResponse;
import com.zirocraft.zirologistics.repository.ItemRepository;
import com.zirocraft.zirologistics.service.ItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    private final ItemRepository itemRepository;

    @Override
    @Transactional
    public ItemResponse createItem(ItemRequest request) {
        if (itemRepository.findBySku(request.getSku()).isPresent()) {
            throw new RuntimeException("SKU " + request.getSku() + " sudah terdaftar!");
        }

        ItemEntity item = ItemEntity.builder()
                .sku(request.getSku())
                .name(request.getName())
                .description(request.getDescription())
                .baseUom(request.getBaseUom().toUpperCase())
                .minStockLevel(request.getMinStockLevel())
                .weightPerUnit(request.getWeightPerUnit()) // Industrial Mapping
                .isDeleted(false)
                .build();

        ItemEntity savedItem = itemRepository.save(item);
        log.info("[ZIROCRAFT-TRACE] SKU {} registered with weight {} KG", savedItem.getSku(), savedItem.getWeightPerUnit());

        return mapToResponse(savedItem);
    }

    @Override
    public Page<ItemResponse> getAllItems(Pageable pageable) {
        return itemRepository.findAllByIsDeletedFalse(pageable)
                .map(this::mapToResponse);
    }

    private ItemResponse mapToResponse(ItemEntity entity) {
        return ItemResponse.builder()
                .publicId(entity.getPublicId())
                .sku(entity.getSku())
                .name(entity.getName())
                .description(entity.getDescription())
                .baseUom(entity.getBaseUom())
                .minStockLevel(entity.getMinStockLevel())
                .weightPerUnit(entity.getWeightPerUnit()) // Industrial Mapping
                .build();
    }
}