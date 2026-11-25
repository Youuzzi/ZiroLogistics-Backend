package com.zirocraft.zirologistics.wms.replenishment.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WmsTenantBusinessDateTest {

    @Test
    void resolvesSameInstantToDifferentTenantLocalBusinessDates() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-25T16:30:00Z"), ZoneOffset.UTC);

        assertEquals(LocalDate.of(2026, 11, 25), WmsTenantBusinessDate.today("Asia/Jakarta", clock));
        assertEquals(LocalDate.of(2026, 11, 26), WmsTenantBusinessDate.today("Pacific/Auckland", clock));
    }

    @Test
    void rejectsMissingOrInvalidTenantTimezone() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-25T16:30:00Z"), ZoneOffset.UTC);

        assertThrows(IllegalStateException.class, () -> WmsTenantBusinessDate.today(null, clock));
        assertThrows(IllegalStateException.class, () -> WmsTenantBusinessDate.today("Not/A-Timezone", clock));
    }
}
