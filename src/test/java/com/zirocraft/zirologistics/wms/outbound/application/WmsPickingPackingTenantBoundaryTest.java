package com.zirocraft.zirologistics.wms.outbound.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryReservationService;
import com.zirocraft.zirologistics.wms.packing.application.WmsPackingManagementService;
import com.zirocraft.zirologistics.wms.picking.application.WmsPickingManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsPickingPackingTenantBoundaryTest {
    private WmsMembershipAccessService accessService;
    private JdbcTemplate jdbc;
    private WmsAuthenticatedPrincipal principal;

    @BeforeEach
    void setUp() {
        accessService = mock(WmsMembershipAccessService.class);
        jdbc = mock(JdbcTemplate.class);
        principal = new WmsAuthenticatedPrincipal(
                "user-public", 20L, "tenant-a", 30L, "membership-a", Set.of());
    }

    @Test
    void foreignPickingTaskIdIsNotFoundInAuthenticatedTenant() {
        when(accessService.resolve("user-public", "membership-a")).thenReturn(access("WMS_PICKING_READ"));
        WmsPickingManagementService service = new WmsPickingManagementService(
                accessService, mock(WmsInventoryReservationService.class),
                mock(WmsInventoryPostingService.class), jdbc, new ObjectMapper());
        when(jdbc.queryForList(contains("WHERE t.tenant_id=? AND t.public_id=?"),
                eq(20L), eq("foreign-picking-task"))).thenReturn(List.of());

        assertThrows(WmsResourceNotFoundException.class,
                () -> service.get(principal, "foreign-picking-task"));

        verify(jdbc).queryForList(contains("WHERE t.tenant_id=? AND t.public_id=?"),
                eq(20L), eq("foreign-picking-task"));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void foreignPackingSessionIdIsNotFoundInAuthenticatedTenant() {
        when(accessService.resolve("user-public", "membership-a")).thenReturn(access("WMS_PACKING_READ"));
        WmsPackingManagementService service = new WmsPackingManagementService(
                accessService, jdbc, new ObjectMapper());
        when(jdbc.queryForList(contains("WHERE ps.tenant_id=? AND ps.public_id=?"),
                eq(20L), eq("foreign-packing-session"))).thenReturn(List.of());

        assertThrows(WmsResourceNotFoundException.class,
                () -> service.get(principal, "foreign-packing-session"));

        verify(jdbc).queryForList(contains("WHERE ps.tenant_id=? AND ps.public_id=?"),
                eq(20L), eq("foreign-packing-session"));
        verifyNoMoreInteractions(jdbc);
    }

    private WmsMembershipAccess access(String permission) {
        return new WmsMembershipAccess(
                30L, "user-public", 20L, "tenant-a", 30L, "membership-a", Set.of(permission));
    }
}
