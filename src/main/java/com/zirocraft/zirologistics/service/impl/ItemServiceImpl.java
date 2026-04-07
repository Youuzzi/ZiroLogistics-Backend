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
        // Gembok SKU: Tidak boleh ada SKU kembar di gudang
        if (itemRepository.findBySku(request.getSku()).isPresent()) {
            log.warn("[ZIROCRAFT-TRACE] Attempt to register duplicate SKU: {}", request.getSku());
            throw new RuntimeException("SKU " + request.getSku() + " sudah terdaftar!");
        }

        ItemEntity item = ItemEntity.builder()
                .sku(request.getSku())
                .name(request.getName())
                .description(request.getDescription())
                .baseUom(request.getBaseUom().toUpperCase())
                .minStockLevel(request.getMinStockLevel())
                .isDeleted(false)
                .build();

        ItemEntity savedItem = itemRepository.save(item);
        log.info("[ZIROCRAFT-TRACE] New Item Registered: {} with Public ID: {}", savedItem.getSku(), savedItem.getPublicId());

        return mapToResponse(savedItem);
    }

    @Override
    public Page<ItemResponse> getAllItems(Pageable pageable) {
        log.info("[ZIROCRAFT-TRACE] Fetching paginated items. Page: {}, Size: {}", pageable.getPageNumber(), pageable.getPageSize());

        // Menggunakan findAllByIsDeletedFalse yang akan kita buat di Repository
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
                .build();
    }
}