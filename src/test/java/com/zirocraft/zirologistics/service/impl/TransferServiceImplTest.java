package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.BinEntity;
import com.zirocraft.zirologistics.entity.InventoryStockEntity;
import com.zirocraft.zirologistics.entity.ItemEntity;
import com.zirocraft.zirologistics.exception.ApiException;
import com.zirocraft.zirologistics.io.request.TransferRequest;
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

class TransferServiceImplTest {

    private ItemRepository itemRepository;
    private BinRepository binRepository;
    private InventoryStockRepository stockRepository;
    private InventoryLedgerRepository ledgerRepository;
    private TransferServiceImpl service;

    @BeforeEach
    void setUp() {
        itemRepository = mock(ItemRepository.class);
        binRepository = mock(BinRepository.class);
        stockRepository = mock(InventoryStockRepository.class);
        ledgerRepository = mock(InventoryLedgerRepository.class);
        service = new TransferServiceImpl(itemRepository, binRepository, stockRepository, ledgerRepository);
    }

    @Test
    void processTransferLocksBinsInStableOrderAndUpdatesBothBalancesAndLedger() {
        ItemEntity item = ItemEntity.builder().sku("SKU-1").baseUom("PCS").weightPerUnit(new BigDecimal("2.00")).build();
        BinEntity source = bin("BIN-Z", "20.00", "100.00");
        BinEntity destination = bin("BIN-A", "10.00", "100.00");
        InventoryStockEntity sourceStock = stock(item, source, "10.00");
        InventoryStockEntity destinationStock = stock(item, destination, "2.00");

        when(ledgerRepository.existsByRequestId("MOVE-1_OUT")).thenReturn(false);
        when(ledgerRepository.existsByRequestId("MOVE-1_IN")).thenReturn(false);
        when(itemRepository.findBySkuForUpdate("SKU-1")).thenReturn(Optional.of(item));
        when(binRepository.findByBinCodeForUpdate("BIN-A")).thenReturn(Optional.of(destination));
        when(binRepository.findByBinCodeForUpdate("BIN-Z")).thenReturn(Optional.of(source));
        when(stockRepository.findByItemSkuAndBinBinCodeAndStatus("SKU-1", "BIN-Z", "AVAILABLE"))
                .thenReturn(Optional.of(sourceStock));
        when(stockRepository.findByItemSkuAndBinBinCodeAndStatus("SKU-1", "BIN-A", "AVAILABLE"))
                .thenReturn(Optional.of(destinationStock));

        service.processTransfer(request("MOVE-1", "BIN-Z", "BIN-A", "3.00"), "operator@example.com");

        assertEquals(0, new BigDecimal("7.00").compareTo(sourceStock.getQuantity()));
        assertEquals(0, new BigDecimal("5.00").compareTo(destinationStock.getQuantity()));
        assertEquals(0, new BigDecimal("14.00").compareTo(source.getCurrentWeightOccupancy()));
        assertEquals(0, new BigDecimal("16.00").compareTo(destination.getCurrentWeightOccupancy()));
        InOrder lockOrder = inOrder(binRepository);
        lockOrder.verify(binRepository).findByBinCodeForUpdate("BIN-A");
        lockOrder.verify(binRepository).findByBinCodeForUpdate("BIN-Z");
        verify(ledgerRepository).save(argThat(entry ->
                "MOVE-1_OUT".equals(entry.getRequestId()) && "TRANSFER_OUT".equals(entry.getTransactionType())));
        verify(ledgerRepository).save(argThat(entry ->
                "MOVE-1_IN".equals(entry.getRequestId()) && "TRANSFER_IN".equals(entry.getTransactionType())));
    }

    @Test
    void processTransferRejectsPreviouslyProcessedRequestIdUsingLedgerSuffix() {
        when(itemRepository.findBySkuForUpdate("SKU-1"))
                .thenReturn(Optional.of(ItemEntity.builder().sku("SKU-1").baseUom("PCS").build()));
        when(ledgerRepository.existsByRequestId("MOVE-2_OUT")).thenReturn(true);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.processTransfer(request("MOVE-2", "BIN-A", "BIN-B", "1.00"),
                        "operator@example.com"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        InOrder order = inOrder(itemRepository, ledgerRepository);
        order.verify(itemRepository).findBySkuForUpdate("SKU-1");
        order.verify(ledgerRepository).existsByRequestId("MOVE-2_OUT");
        verifyNoInteractions(binRepository, stockRepository);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void processTransferRechecksDuplicateRequestAfterWaitingForItemLock() {
        when(ledgerRepository.existsByRequestId("MOVE-RACE_OUT")).thenReturn(true);
        when(itemRepository.findBySkuForUpdate("SKU-1"))
                .thenReturn(Optional.of(ItemEntity.builder().sku("SKU-1").baseUom("PCS").build()));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.processTransfer(request("MOVE-RACE", "BIN-A", "BIN-B", "1.00"),
                        "operator@example.com"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        InOrder order = inOrder(itemRepository, ledgerRepository);
        order.verify(itemRepository).findBySkuForUpdate("SKU-1");
        order.verify(ledgerRepository).existsByRequestId("MOVE-RACE_OUT");
        verifyNoInteractions(binRepository, stockRepository);
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    void processTransferRejectsSourceOccupancyThatWouldBecomeNegative() {
        ItemEntity item = ItemEntity.builder().sku("SKU-1").baseUom("PCS").weightPerUnit(new BigDecimal("2.00")).build();
        BinEntity source = bin("BIN-A", "1.00", "100.00");
        BinEntity destination = bin("BIN-B", "10.00", "100.00");
        InventoryStockEntity sourceStock = stock(item, source, "10.00");

        when(ledgerRepository.existsByRequestId("MOVE-3_OUT")).thenReturn(false);
        when(ledgerRepository.existsByRequestId("MOVE-3_IN")).thenReturn(false);
        when(itemRepository.findBySkuForUpdate("SKU-1")).thenReturn(Optional.of(item));
        when(binRepository.findByBinCodeForUpdate("BIN-A")).thenReturn(Optional.of(source));
        when(binRepository.findByBinCodeForUpdate("BIN-B")).thenReturn(Optional.of(destination));
        when(stockRepository.findByItemSkuAndBinBinCodeAndStatus("SKU-1", "BIN-A", "AVAILABLE"))
                .thenReturn(Optional.of(sourceStock));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.processTransfer(request("MOVE-3", "BIN-A", "BIN-B", "1.00"),
                        "operator@example.com"));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        verify(stockRepository, never()).save(any());
        verify(binRepository, never()).save(any());
        verify(ledgerRepository, never()).save(any());
    }

    private BinEntity bin(String code, String occupancy, String capacity) {
        return BinEntity.builder().binCode(code)
                .currentWeightOccupancy(new BigDecimal(occupancy))
                .maxWeightCapacity(new BigDecimal(capacity)).build();
    }

    private InventoryStockEntity stock(ItemEntity item, BinEntity bin, String quantity) {
        return InventoryStockEntity.builder().item(item).bin(bin)
                .quantity(new BigDecimal(quantity)).status("AVAILABLE").build();
    }

    private TransferRequest request(String requestId, String source, String destination, String quantity) {
        TransferRequest request = new TransferRequest();
        request.setRequestId(requestId);
        request.setSku("SKU-1");
        request.setSourceBinCode(source);
        request.setDestinationBinCode(destination);
        request.setQuantity(new BigDecimal(quantity));
        return request;
    }

    @Test
    void processTransferRejectsFractionalBoxQuantityBeforeBinOrStockMutation() {
        ItemEntity item = ItemEntity.builder().sku("SKU-BOX").baseUom("BOX").build();
        TransferRequest request = request("MOVE-PRECISION", "BIN-A", "BIN-B", "2.5");
        request.setSku("SKU-BOX");
        when(itemRepository.findBySkuForUpdate("SKU-BOX")).thenReturn(Optional.of(item));

        ApiException exception = assertThrows(ApiException.class,
                () -> service.processTransfer(request, "operator@example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        verifyNoInteractions(binRepository, stockRepository);
        verify(ledgerRepository, never()).save(any());
    }

}
