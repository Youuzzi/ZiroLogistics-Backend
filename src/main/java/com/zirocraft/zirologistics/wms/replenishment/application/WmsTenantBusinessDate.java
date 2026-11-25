package com.zirocraft.zirologistics.wms.replenishment.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Resolves the current business date using the tenant's validated IANA timezone.
 */
final class WmsTenantBusinessDate {
    private WmsTenantBusinessDate() {}

    static LocalDate today(String tenantTimezone, Clock clock) {
        if (tenantTimezone == null || tenantTimezone.isBlank()) {
            throw new IllegalStateException("Tenant timezone is not configured");
        }
        try {
            return LocalDate.now(Objects.requireNonNull(clock, "clock must not be null")
                    .withZone(ZoneId.of(tenantTimezone)));
        } catch (DateTimeException invalidTimezone) {
            throw new IllegalStateException("Tenant timezone is invalid", invalidTimezone);
        }
    }
}
