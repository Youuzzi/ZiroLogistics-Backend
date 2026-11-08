package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.exception.ApiException;
import com.zirocraft.zirologistics.io.request.InboundRequest;
import com.zirocraft.zirologistics.repository.BinRepository;
import com.zirocraft.zirologistics.repository.InventoryLedgerRepository;
import com.zirocraft.zirologistics.repository.InventoryStockRepository;
import com.zirocraft.zirologistics.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class InboundServiceImplTest {

    private ItemRepository itemRepository;
    private BinRepository binRepository;
    private InventoryStockRepository stockRepository;
    private InventoryLedgerRepository ledgerRepository;
    private InboundServiceImpl service;

    @BeforeEach
    void setUp() {
        itemRepository = mock(ItemRepository.class);
        binRepository = mock(BinRepository.class);
        stockRepository = mock(InventoryStockRepository.class);
        ledgerRepository = mock(InventoryLedgerRepository.class);
        service = new InboundServiceImpl(itemRepository, binRepository, stockRepository, ledgerRepository);
    }

    @Test
    void processInboundChecksDuplicateRequestAfterAcquiringItemLock() {
        InboundRequest request = new InboundRequest();
        request.setRequestId("IN-RACE");
        request.setSku("SKU-1");
        request.setBinCode("BIN-A");
        request.setQuantity(new BigDecimal("1.00"));

        when(itemRepository.findBySkuForUpdate("SKU-1"))
                .thenReturn(Optional.of(ItemEntity.builder().sku("SKU-1").baseUom("PCS").build()));
        when(ledgerRepository.existsByRequestId("IN-RACE")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.processInbound(request, "operator@example.com"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        InOrder order = inOrder(itemRepository, ledgerRepository);
        order.verify(itemRepository).findBySkuForUpdate("SKU-1");
        order.verify(ledgerRepository).existsByRequestId("IN-RACE");
        verifyNoInteractions(binRepository, stockRepository);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void processInboundRejectsFractionalPieceQuantityBeforeBinOrStockMutation() {
        ItemEntity item = ItemEntity.builder().sku("SKU-PCS").baseUom("PCS").build();
        InboundRequest request = new InboundRequest();
        request.setRequestId("IN-FRACTION");
        request.setSku("SKU-PCS");
        request.setBinCode("BIN-A");
        request.setQuantity(new BigDecimal("1.5"));
        when(itemRepository.findBySkuForUpdate("SKU-PCS")).thenReturn(Optional.of(item));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.processInbound(request, "operator@example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(binRepository, stockRepository);
        verify(ledgerRepository, never()).save(any());
    }

}
