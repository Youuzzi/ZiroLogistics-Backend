package com.zirocraft.zirologistics.service.validation;

import com.zirocraft.zirologistics.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * Guards values against the full MySQL DECIMAL(precision, scale) envelope
 * before JPA attempts persistence.
 */
public final class DecimalPrecisionPolicy {

    private DecimalPrecisionPolicy() {
    }

    public static void validate(BigDecimal value, int precision, int scale, String fieldName) {
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, fieldName + " wajib diisi.");
        }

        BigDecimal normalized = value.stripTrailingZeros();
        int effectiveScale = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        int maxIntegerDigits = precision - scale;

        if (effectiveScale > scale || integerDigits > maxIntegerDigits) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    fieldName + " melebihi batas DECIMAL(" + precision + "," + scale + ").");
        }
    }
}
