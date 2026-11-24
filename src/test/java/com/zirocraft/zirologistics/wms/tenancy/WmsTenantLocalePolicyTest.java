package com.zirocraft.zirologistics.wms.tenancy;

import com.zirocraft.zirologistics.wms.tenancy.domain.WmsTenantLocalePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WmsTenantLocalePolicyTest {

    @Test
    void acceptsCanonicalRegionTimezonesAndUtc() {
        assertEquals("Asia/Jakarta", WmsTenantLocalePolicy.normalizeTimezone(" Asia/Jakarta "));
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
    void normalizesAndValidatesIso4217CurrencyCodes() {
        assertEquals("IDR", WmsTenantLocalePolicy.normalizeCurrency(" idr "));
        assertEquals("USD", WmsTenantLocalePolicy.normalizeCurrency("USD"));
        assertThrows(IllegalArgumentException.class,
                () -> WmsTenantLocalePolicy.normalizeCurrency("ZZZ"));
        assertThrows(IllegalArgumentException.class,
                () -> WmsTenantLocalePolicy.normalizeCurrency("ID"));
    }
}
