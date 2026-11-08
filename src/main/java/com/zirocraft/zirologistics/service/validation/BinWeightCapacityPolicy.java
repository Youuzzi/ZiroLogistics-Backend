package com.zirocraft.zirologistics.service.validation;

import com.zirocraft.zirologistics.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * Validates bin physical-weight limits before DECIMAL(38,9) persistence.
 */
public final class BinWeightCapacityPolicy {

    private static final int MAX_FRACTION_DIGITS = 9;

    private BinWeightCapacityPolicy() {
    }

    public static void validate(BigDecimal maxCapacity, BigDecimal minThreshold) {
        if (maxCapacity == null || maxCapacity.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Kapasitas maksimum bin harus lebih dari 0.");
        }
        if (minThreshold == null || minThreshold.signum() < 0
                || minThreshold.compareTo(maxCapacity) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Minimum threshold harus berada di antara 0 dan kapasitas maksimum.");
        }
        if (effectiveScale(maxCapacity) > MAX_FRACTION_DIGITS
                || effectiveScale(minThreshold) > MAX_FRACTION_DIGITS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Kapasitas dan threshold bin maksimal memiliki 9 angka desimal.");
        }
    }

    private static int effectiveScale(BigDecimal value) {
        return Math.max(value.stripTrailingZeros().scale(), 0);
    }
}
