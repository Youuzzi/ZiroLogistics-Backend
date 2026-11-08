package com.zirocraft.zirologistics.service.validation;

import com.zirocraft.zirologistics.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinWeightCapacityPolicyTest {

    @Test
    void acceptsValidCapacityAndNineDigitThreshold() {
        assertDoesNotThrow(() -> BinWeightCapacityPolicy.validate(
                new BigDecimal("100.000000000"), new BigDecimal("0.000000001")));
    }

    @Test
    void allowsZeroThresholdAndTrailingZeroes() {
        assertDoesNotThrow(() -> BinWeightCapacityPolicy.validate(
                new BigDecimal("100.0000000000"), BigDecimal.ZERO));
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(ApiException.class,
                () -> BinWeightCapacityPolicy.validate(BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(ApiException.class,
                () -> BinWeightCapacityPolicy.validate(new BigDecimal("-1"), BigDecimal.ZERO));
    }

    @Test
    void rejectsThresholdOutsideCapacityRange() {
        assertThrows(ApiException.class,
                () -> BinWeightCapacityPolicy.validate(new BigDecimal("10"), new BigDecimal("-0.1")));
        assertThrows(ApiException.class,
                () -> BinWeightCapacityPolicy.validate(new BigDecimal("10"), new BigDecimal("10.1")));
    }

    @Test
    void rejectsMoreThanNineEffectiveFractionalDigits() {
        assertThrows(ApiException.class,
                () -> BinWeightCapacityPolicy.validate(new BigDecimal("1.0000000001"), BigDecimal.ZERO));
        assertThrows(ApiException.class,
                () -> BinWeightCapacityPolicy.validate(new BigDecimal("1"), new BigDecimal("0.0000000001")));
    }    @Test
    void rejectsCapacityOutsideDecimalIntegerRange() {
        assertThrows(ApiException.class,
                () -> BinWeightCapacityPolicy.validate(new BigDecimal("1E+29"), BigDecimal.ZERO));
    }

}
