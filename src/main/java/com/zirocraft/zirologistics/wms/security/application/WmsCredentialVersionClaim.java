package com.zirocraft.zirologistics.wms.security.application;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Strict parser for the numeric credential-version claim carried by WMS tokens. */
public final class WmsCredentialVersionClaim {

    private WmsCredentialVersionClaim() {
    }

    public static Long parse(Object claim) {
        try {
            if (claim instanceof Byte value) return nonNegative(value.longValue());
            if (claim instanceof Short value) return nonNegative(value.longValue());
            if (claim instanceof Integer value) return nonNegative(value.longValue());
            if (claim instanceof Long value) return nonNegative(value);
            if (claim instanceof BigInteger value) return nonNegative(value.longValueExact());
            if (claim instanceof BigDecimal value) return nonNegative(value.longValueExact());
        } catch (ArithmeticException ignored) {
            return null;
        }
        return null;
    }

    private static Long nonNegative(long value) {
        return value < 0 ? null : value;
    }
}
