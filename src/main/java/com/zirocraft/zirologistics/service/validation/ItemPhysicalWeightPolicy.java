package com.zirocraft.zirologistics.service.validation;

import com.zirocraft.zirologistics.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Validates that the configured weight is expressed in KG per one Base UOM unit.
 * Density-dependent units (ML) and item-dependent units (PCS/BOX) require
 * product-specific positive weights.
 */
public final class ItemPhysicalWeightPolicy {

    private static final Map<String, BigDecimal> FIXED_KG_PER_UNIT = Map.of(
            "KG", new BigDecimal("1.000000"),
            "GR", new BigDecimal("0.001000")
    );

    private ItemPhysicalWeightPolicy() {
    }

    public static void validate(String baseUom, BigDecimal weightPerUnit) {
        if (weightPerUnit == null || weightPerUnit.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Berat per unit harus lebih dari 0 KG.");
        }

        String normalized = baseUom == null ? "" : baseUom.trim().toUpperCase(Locale.ROOT);
        if (!Map.of("KG", true, "GR", true, "PCS", true, "BOX", true, "ML", true).containsKey(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Base UOM barang tidak didukung.");
        }

        BigDecimal fixedExpected = FIXED_KG_PER_UNIT.get(normalized);
        if (fixedExpected != null && weightPerUnit.compareTo(fixedExpected) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Weight per unit untuk UOM " + normalized + " harus " + fixedExpected + " KG per unit.");
        }

        if (weightPerUnit.stripTrailingZeros().scale() > 6) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Weight per unit maksimal memiliki 6 angka desimal.");
        }
    }
}
