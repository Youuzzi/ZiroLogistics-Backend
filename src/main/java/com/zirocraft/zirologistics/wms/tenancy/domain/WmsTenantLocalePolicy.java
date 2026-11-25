package com.zirocraft.zirologistics.wms.tenancy.domain;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * Canonical tenant locale validation shared by bootstrap, provisioning, and profile updates.
 * Tenant timezones are region-based TZDB identifiers; currency values are supported ISO-4217 codes.
 */
public final class WmsTenantLocalePolicy {
    private WmsTenantLocalePolicy() {}

    public static String normalizeTimezone(String value) {
        String timezone = Objects.requireNonNull(value, "timezone").strip();
        if (timezone.isEmpty() || timezone.length() > 60
                || (!ZoneId.getAvailableZoneIds().contains(timezone) && !"UTC".equals(timezone))) {
            throw new IllegalArgumentException("Tenant timezone is invalid");
        }
        return timezone;
    }

    public static String normalizeCurrency(String value) {
        String code = Objects.requireNonNull(value, "currency").strip().toUpperCase(Locale.ROOT);
        if (code.length() != 3) {
            throw new IllegalArgumentException("Tenant currency is invalid");
        }
        try {
            Currency currency = Currency.getInstance(code);
            if (!currency.getCurrencyCode().equals(code)) {
                throw new IllegalArgumentException("Tenant currency is invalid");
            }
            return code;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Tenant currency is invalid");
        }
    }
}
