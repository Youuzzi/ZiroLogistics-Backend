package com.zirocraft.zirologistics.service.validation;

import com.zirocraft.zirologistics.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemPhysicalWeightPolicyTest {

    @Test
    void kilogramBaseUomMustWeighExactlyOneKilogramPerUnit() {
        assertDoesNotThrow(() -> ItemPhysicalWeightPolicy.validate("KG", new BigDecimal("1.000000")));
        assertThrows(ApiException.class,
                () -> ItemPhysicalWeightPolicy.validate("KG", new BigDecimal("0.000900")));
    }

    @Test
    void gramBaseUomMustUseOneThousandthKilogramPerUnit() {
        assertDoesNotThrow(() -> ItemPhysicalWeightPolicy.validate("GR", new BigDecimal("0.001000")));
        assertThrows(ApiException.class,
                () -> ItemPhysicalWeightPolicy.validate("GR", new BigDecimal("1.000000")));
    }

    @Test
    void milliliterAndPieceWeightsRemainProductSpecific() {
        assertDoesNotThrow(() -> ItemPhysicalWeightPolicy.validate("ML", new BigDecimal("0.000900")));
        assertDoesNotThrow(() -> ItemPhysicalWeightPolicy.validate("PCS", new BigDecimal("0.250000")));
    }

    @Test
    void rejectsNullZeroNegativeUnsupportedAndExcessPrecision() {
        assertThrows(ApiException.class, () -> ItemPhysicalWeightPolicy.validate("KG", null));
        assertThrows(ApiException.class, () -> ItemPhysicalWeightPolicy.validate("PCS", BigDecimal.ZERO));
        assertThrows(ApiException.class,
                () -> ItemPhysicalWeightPolicy.validate("PCS", new BigDecimal("-0.1")));
        assertThrows(ApiException.class,
                () -> ItemPhysicalWeightPolicy.validate("L", new BigDecimal("1.0")));
        assertThrows(ApiException.class,
                () -> ItemPhysicalWeightPolicy.validate("ML", new BigDecimal("0.0000001")));
    }

    @Test
    void rejectsWeightOutsideDecimalIntegerRange() {
        assertThrows(ApiException.class,
                () -> ItemPhysicalWeightPolicy.validate("PCS", new BigDecimal("1E+32")));
    }

}
