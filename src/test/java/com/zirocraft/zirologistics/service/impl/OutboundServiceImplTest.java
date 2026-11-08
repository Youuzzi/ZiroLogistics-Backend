package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.BinEntity;
import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.exception.ApiException;
import com.zirocraft.zirologistics.io.request.OutboundRequest;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutboundServiceImplTest {

    private ItemRepository itemRepository;
    private BinRepository binRepository;
    private InventoryStockRepository stockRepository;
    private InventoryLedgerRepository ledgerRepository;
    private OutboundServiceImpl service;

    @BeforeEach
    void setUp() {
        itemRepository = mock(ItemRepository.class);
        binRepository = mock(BinRepository.class);
        stockRepository = mock(InventoryStockRepository.class);
        ledgerRepository = mock(InventoryLedgerRepository.class);
        service = new OutboundServiceImpl(itemRepository, binRepository, stockRepository, ledgerRepository);
    }

    @Test
    void processOutboundReducesStockAndBinOccupancyAndWritesLedger() {
        ItemEntity item = ItemEntity.builder().sku("SKU-1").weightPerUnit(new BigDecimal("2.00")).build();
        BinEntity bin = BinEntity.builder().binCode("BIN-A")
                .currentWeightOccupancy(new BigDecimal("20.00")).build();
        InventoryStockEntity stock = InventoryStockEntity.builder().item(item).bin(bin)
                .quantity(new BigDecimal("10.00")).status("AVAILABLE").build();

        when(ledgerRepository.existsByRequestId("OUT-1")).thenReturn(false);
        when(itemRepository.findBySkuForUpdate("SKU-1")).thenReturn(Optional.of(item));
        when(binRepository.findByBinCodeForUpdate("BIN-A")).thenReturn(Optional.of(bin));
        when(stockRepository.findByItemSkuAndBinBinCodeAndStatus("SKU-1", "BIN-A", "AVAILABLE"))
                .thenReturn(Optional.of(stock));

        service.processOutbound(request("OUT-1", "3.00"), "operator@example.com");

        assertEquals(0, new BigDecimal("7.00").compareTo(stock.getQuantity()));
        assertEquals(0, new BigDecimal("14.00").compareTo(bin.getCurrentWeightOccupancy()));
        verify(stockRepository).save(stock);
        verify(binRepository).save(bin);
        verify(ledgerRepository).save(argThat(ledger ->
                "OUTBOUND".equals(ledger.getTransactionType())
                        && new BigDecimal("-3.00").compareTo(ledger.getQuantityChange()) == 0
                        && new BigDecimal("7.00").compareTo(ledger.getBalanceAfter()) == 0));
    }

    @Test
    void processOutboundRejectsOccupancyThatWouldBecomeNegative() {
        ItemEntity item = ItemEntity.builder().sku("SKU-1").weightPerUnit(new BigDecimal("2.00")).build();
        BinEntity bin = BinEntity.builder().binCode("BIN-A")
                .currentWeightOccupancy(new BigDecimal("1.00")).build();
        InventoryStockEntity stock = InventoryStockEntity.builder().item(item).bin(bin)
                .quantity(new BigDecimal("10.00")).status("AVAILABLE").build();

        when(ledgerRepository.existsByRequestId("OUT-2")).thenReturn(false);
        when(itemRepository.findBySkuForUpdate("SKU-1")).thenReturn(Optional.of(item));
        when(binRepository.findByBinCodeForUpdate("BIN-A")).thenReturn(Optional.of(bin));
        when(stockRepository.findByItemSkuAndBinBinCodeAndStatus("SKU-1", "BIN-A", "AVAILABLE"))
                .thenReturn(Optional.of(stock));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.processOutbound(request("OUT-2", "1.00"), "operator@example.com"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(stockRepository, never()).save(any());
        verify(binRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void processOutboundRechecksDuplicateRequestAfterWaitingForItemLock() {
        ItemEntity item = ItemEntity.builder().sku("SKU-1").build();
        when(ledgerRepository.existsByRequestId("OUT-RACE")).thenReturn(true);
        when(itemRepository.findBySkuForUpdate("SKU-1")).thenReturn(Optional.of(item));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.processOutbound(request("OUT-RACE", "1.00"), "operator@example.com"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        InOrder order = inOrder(itemRepository, ledgerRepository);
        order.verify(itemRepository).findBySkuForUpdate("SKU-1");
        order.verify(ledgerRepository).existsByRequestId("OUT-RACE");
        verifyNoInteractions(binRepository, stockRepository);
        verify(ledgerRepository, never()).save(any());
    }

    private OutboundRequest request(String requestId, String quantity) {
        OutboundRequest request = new OutboundRequest();
        request.setRequestId(requestId);
        request.setSku("SKU-1");
        request.setBinCode("BIN-A");
        request.setQuantity(new BigDecimal(quantity));
        return request;
    }

    @Test
    void processOutboundRejectsKgPrecisionBeyondThreeDigitsBeforeBinOrStockMutation() {
        ItemEntity item = ItemEntity.builder().sku("SKU-KG").baseUom("KG").build();
        OutboundRequest request = request("OUT-PRECISION", "1.0001");
        request.setSku("SKU-KG");
        when(itemRepository.findBySkuForUpdate("SKU-KG")).thenReturn(Optional.of(item));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.processOutbound(request, "operator@example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(binRepository, stockRepository);
        verify(ledgerRepository, never()).save(any());
    }

}
