package com.zirocraft.zirologistics.wms.inventory.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class WmsInventoryPostingQuantityBoundaryTest {
    @Test
    void acceptsMaximumRepresentableDecimal19Scale4Quantity() {
        assertEquals(new BigDecimal("999999999999999.9999"),
                WmsInventoryPostingService.normalizeQuantity(new BigDecimal("999999999999999.9999")));
    }

    @Test
    void rejectsQuantityJustAboveDecimal19Scale4Maximum() {
        assertThrows(IllegalArgumentException.class,
                () -> WmsInventoryPostingService.normalizeQuantity(new BigDecimal("1000000000000000.0000")));
    }

    @Test
    void rejectsNullZeroNegativeAndRoundingRequiredQuantities() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> WmsInventoryPostingService.normalizeQuantity(null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> WmsInventoryPostingService.normalizeQuantity(BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> WmsInventoryPostingService.normalizeQuantity(new BigDecimal("-0.0001"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> WmsInventoryPostingService.normalizeQuantity(new BigDecimal("0.00001")))
        );
    }

    @Test
    void acceptsTrailingZeroPrecisionWithoutRounding() {
        assertEquals(new BigDecimal("2.5000"),
                WmsInventoryPostingService.normalizeQuantity(new BigDecimal("2.50000")));
    }
}
