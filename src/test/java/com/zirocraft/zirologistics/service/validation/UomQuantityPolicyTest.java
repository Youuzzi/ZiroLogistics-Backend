package com.zirocraft.zirologistics.service.validation;

import com.zirocraft.zirologistics.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UomQuantityPolicyTest {

    @Test
    void acceptsWholeUnitQuantitiesForCountBasedUoms() {
        assertDoesNotThrow(() -> UomQuantityPolicy.validate("PCS", new BigDecimal("12")));
        assertDoesNotThrow(() -> UomQuantityPolicy.validate("BOX", new BigDecimal("5.000")));
        assertDoesNotThrow(() -> UomQuantityPolicy.validate("GR", new BigDecimal("500")));
        assertDoesNotThrow(() -> UomQuantityPolicy.validate("ML", new BigDecimal("750")));
    }

    @Test
    void rejectsFractionalQuantitiesForCountBasedUoms() {
        assertInvalid("PCS", "10.5");
        assertInvalid("BOX", "2.25");
        assertInvalid("GR", "500.5");
        assertInvalid("ML", "750.5");
    }

    @Test
    void acceptsKgUpToThreeFractionDigits() {
        assertDoesNotThrow(() -> UomQuantityPolicy.validate("KG", new BigDecimal("1")));
        assertDoesNotThrow(() -> UomQuantityPolicy.validate("KG", new BigDecimal("1.250")));
        assertDoesNotThrow(() -> UomQuantityPolicy.validate("KG", new BigDecimal("1.2500")));
    }

    @Test
    void rejectsKgWithMoreThanThreeEffectiveFractionDigits() {
        assertInvalid("KG", "1.2505");
    }

    @Test
    void rejectsNullZeroAndNegativeQuantities() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> UomQuantityPolicy.validate("PCS", null)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> UomQuantityPolicy.validate("PCS", BigDecimal.ZERO)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> UomQuantityPolicy.validate("KG", new BigDecimal("-0.001"))).getStatus());
    }

    @Test
    void rejectsUnsupportedUom() {
        assertInvalid("LBS", "1");
    }

    private void assertInvalid(String uom, String quantity) {
        ApiException exception = assertThrows(ApiException.class,
                () -> UomQuantityPolicy.validate(uom, new BigDecimal(quantity)));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }
}
