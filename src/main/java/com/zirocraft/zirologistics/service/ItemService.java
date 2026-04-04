package com.zirocraft.zirologistics.service;

import com.zirocraft.zirologistics.io.request.ItemRequest;
import com.zirocraft.zirologistics.io.response.ItemResponse;
import java.util.List;

public interface ItemService {
    ItemResponse createItem(ItemRequest request);
    List<ItemResponse> getAllItems();
}