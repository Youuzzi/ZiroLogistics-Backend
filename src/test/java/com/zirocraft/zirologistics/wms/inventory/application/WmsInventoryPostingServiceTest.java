package com.zirocraft.zirologistics.wms.inventory.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WmsInventoryPostingServiceTest {
    @Test
    void signedMovementDeltasUseTheRequestedQuantityAtFourDecimalPlaces() {
        BigDecimal quantity = new BigDecimal("12.3456");
        assertEquals(new BigDecimal("12.3456"), WmsInventoryPostingService.signedDelta("RECEIPT", 0, quantity));
        assertEquals(new BigDecimal("-12.3456"), WmsInventoryPostingService.signedDelta("ISSUE", 0, quantity));
        assertEquals(new BigDecimal("-12.3456"), WmsInventoryPostingService.signedDelta("TRANSFER", 0, quantity));
        assertEquals(new BigDecimal("12.3456"), WmsInventoryPostingService.signedDelta("TRANSFER", 1, quantity));
        assertEquals(new BigDecimal("-12.3456"), WmsInventoryPostingService.signedDelta("DAMAGE", 0, quantity));
        assertEquals(new BigDecimal("12.3456"), WmsInventoryPostingService.signedDelta("DAMAGE", 1, quantity));
        assertEquals(new BigDecimal("-12.3456"), WmsInventoryPostingService.signedDelta("STATUS_CHANGE", 0, quantity));
        assertEquals(new BigDecimal("12.3456"), WmsInventoryPostingService.signedDelta("STATUS_CHANGE", 1, quantity));
    }

    @Test
    void signedMovementDeltasPreserveSubUnitQuantitiesWithoutFloatingPointConversion() {
        BigDecimal quantity = new BigDecimal("0.0001");
        assertEquals(new BigDecimal("0.0001"), WmsInventoryPostingService.signedDelta("ADJUSTMENT_IN", 0, quantity));
        assertEquals(new BigDecimal("-0.0001"), WmsInventoryPostingService.signedDelta("ADJUSTMENT_OUT", 0, quantity));
    }
}