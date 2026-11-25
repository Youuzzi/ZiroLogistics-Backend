package com.zirocraft.zirologistics.wms.tenancy.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WmsTenantLocalePolicyTest {
    @Test
    void acceptsRegionTimezonesAndUtc() {
        assertEquals("Asia/Jakarta", WmsTenantLocalePolicy.normalizeTimezone("Asia/Jakarta"));
        assertEquals("UTC", WmsTenantLocalePolicy.normalizeTimezone("UTC"));
    }

    @Test
    void rejectsFixedOffsetsAndUnknownTimezoneIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> WmsTenantLocalePolicy.normalizeTimezone("+07:00"));
        assertThrows(IllegalArgumentException.class,
                () -> WmsTenantLocalePolicy.normalizeTimezone("Not/A-Timezone"));
    }

    @Test
    void normalizesAndValidatesSupportedIsoCurrencies() {
        assertEquals("IDR", WmsTenantLocalePolicy.normalizeCurrency("idr"));
        assertEquals("USD", WmsTenantLocalePolicy.normalizeCurrency(" USD "));
    }

    @Test
    void rejectsUnknownCurrencyCodes() {
        assertThrows(IllegalArgumentException.class,
                () -> WmsTenantLocalePolicy.normalizeCurrency("ZZZ"));
        assertThrows(IllegalArgumentException.class,
                () -> WmsTenantLocalePolicy.normalizeCurrency("ID"));
    }
}
