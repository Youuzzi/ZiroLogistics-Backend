package com.zirocraft.zirologistics.wms.tenancy.domain;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;

/**
 * Canonical validation and normalization rules for tenant business locale settings.
 *
 * <p>Tenant timezones are Java ZoneId region identifiers (plus UTC), not arbitrary fixed
 * offsets. Currency values must be recognized ISO-4217 codes.</p>
 */
public final class WmsTenantLocalePolicy {
    private WmsTenantLocalePolicy() {
    }

    public static String normalizeTimezone(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Timezone is invalid");
        }
        String timezone = value.strip();
        if (timezone.isEmpty() || timezone.length() > 60
                || (!ZoneId.getAvailableZoneIds().contains(timezone) && !"UTC".equals(timezone))) {
            throw new IllegalArgumentException("Timezone is invalid");
        }
        return timezone;
    }

    public static String normalizeCurrency(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Default currency is invalid");
        }
        String code = value.strip().toUpperCase(Locale.ROOT);
        if (code.length() != 3) {
            throw new IllegalArgumentException("Default currency is invalid");
        }
        try {
            Currency currency = Currency.getInstance(code);
            if (currency == null || !currency.getCurrencyCode().equals(code)) {
                throw new IllegalArgumentException("Default currency is invalid");
            }
            return code;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Default currency is invalid");
        }
    }
}
