package com.zirocraft.zirologistics.wms.reporting.application;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsOperationalReportingTenantBoundaryTest {
    private WmsMembershipAccessService accessService;
    private JdbcTemplate jdbc;
    private WmsOperationalReportingService service;
    private WmsAuthenticatedPrincipal principal;

    @BeforeEach
    void setUp() {
        accessService = mock(WmsMembershipAccessService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new WmsOperationalReportingService(accessService, jdbc);
        principal = new WmsAuthenticatedPrincipal(
                "user-public", 20L, "tenant-a", 30L, "membership-a", Set.of());
    }

    @Test
    void authenticatedMemberWithoutReportingPermissionIsDeniedBeforeAnyReportQuery() {
        when(accessService.resolve("user-public", "membership-a")).thenReturn(access(Set.of()));

        assertThrows(AccessDeniedException.class, () -> service.operationalSummary(principal));

        verifyNoInteractions(jdbc);
    }

    @Test
    void mismatchedTenantContextIsDeniedBeforeAnyReportQuery() {
        when(accessService.resolve("user-public", "membership-a"))
                .thenReturn(new WmsMembershipAccess(
                        30L, "user-public", 10L, "tenant-a", 30L, "membership-a",
                        Set.of("WMS_OPERATIONAL_REPORT_READ")));

        assertThrows(AccessDeniedException.class, () -> service.operationalSummary(principal));

        verifyNoInteractions(jdbc);
    }

    @Test
    void everyWorkflowSummaryQueryIsConstrainedToResolvedTenant() {
        when(accessService.resolve("user-public", "membership-a"))
                .thenReturn(access(Set.of("WMS_OPERATIONAL_REPORT_READ")));
        when(jdbc.queryForObject(eq("SELECT UTC_TIMESTAMP(6)"), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        when(jdbc.queryForMap(contains("FROM inventory_balances"), eq(20L))).thenReturn(Map.of());
        when(jdbc.queryForList(anyString(), eq(20L))).thenReturn(List.of());

        service.operationalSummary(principal);

        verify(jdbc).queryForMap(contains("WHERE tenant_id = ?"), eq(20L));
        verify(jdbc, times(7)).queryForList(contains("WHERE tenant_id = ?"), eq(20L));
        verify(jdbc, never()).queryForList(contains("WHERE tenant_id = ?"), eq(10L));
    }

    private WmsMembershipAccess access(Set<String> permissions) {
        return new WmsMembershipAccess(
                30L, "user-public", 20L, "tenant-a", 30L, "membership-a", permissions);
    }
}
