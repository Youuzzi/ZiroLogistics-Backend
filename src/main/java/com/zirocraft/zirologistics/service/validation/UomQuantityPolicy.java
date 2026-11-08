package com.zirocraft.zirologistics.service.validation;

import com.zirocraft.zirologistics.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Central validation policy for quantities expressed in an item's Base UOM.
 *
 * Trailing zeroes are ignored when determining effective numeric precision.
 */
public final class UomQuantityPolicy {

    private static final Map<String, Integer> MAX_FRACTION_DIGITS = Map.of(
            "PCS", 0,
            "BOX", 0,
            "KG", 3,
            "GR", 0,
            "ML", 0
    );

    private UomQuantityPolicy() {
    }

    public static void validate(String baseUom, BigDecimal quantity) {
        if (quantity == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Jumlah barang wajib diisi.");
        }

        if (quantity.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Jumlah harus lebih dari 0.");
        }

        String normalizedUom = baseUom == null ? "" : baseUom.trim().toUpperCase(Locale.ROOT);
        Integer maxFractionDigits = MAX_FRACTION_DIGITS.get(normalizedUom);
        if (maxFractionDigits == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Base UOM barang tidak didukung.");
        }

        int effectiveScale = Math.max(quantity.stripTrailingZeros().scale(), 0);
        if (effectiveScale > maxFractionDigits) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Jumlah untuk UOM " + normalizedUom + " maksimal memiliki "
                            + maxFractionDigits + " angka desimal.");
        }
    }
}
