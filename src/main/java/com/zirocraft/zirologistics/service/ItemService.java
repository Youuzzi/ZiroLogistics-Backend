package com.zirocraft.zirologistics.service;

import com.zirocraft.zirologistics.io.request.ItemRequest;
import com.zirocraft.zirologistics.io.response.ItemResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ItemService {
    ItemResponse createItem(ItemRequest request);

    // Industrial Standard: Wajib Pagination untuk skalabilitas jutaan data
    Page<ItemResponse> getAllItems(Pageable pageable);
}