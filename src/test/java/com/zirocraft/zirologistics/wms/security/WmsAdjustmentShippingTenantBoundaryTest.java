package com.zirocraft.zirologistics.wms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.adjustment.application.WmsInventoryAdjustmentService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.shipping.application.WmsShippingManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsAdjustmentShippingTenantBoundaryTest {
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
    void foreignInventoryAdjustmentIdIsNotFoundInAuthenticatedTenant() {
        when(accessService.resolve("user-public", "membership-a")).thenReturn(access("WMS_INVENTORY_ADJUSTMENT_READ"));
        WmsInventoryAdjustmentService service = new WmsInventoryAdjustmentService(
                accessService, mock(WmsInventoryPostingService.class), jdbc, new ObjectMapper());
        when(jdbc.queryForList(contains("WHERE a.tenant_id = ? AND a.public_id = ?"),
                eq(20L), eq("foreign-adjustment"))).thenReturn(List.of());

        assertThrows(WmsResourceNotFoundException.class,
                () -> service.get(principal, "foreign-adjustment"));

        verify(jdbc).queryForList(contains("WHERE a.tenant_id = ? AND a.public_id = ?"),
                eq(20L), eq("foreign-adjustment"));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void foreignShipmentIdIsNotFoundInAuthenticatedTenant() {
        when(accessService.resolve("user-public", "membership-a")).thenReturn(access("WMS_SHIPPING_READ"));
        WmsShippingManagementService service = new WmsShippingManagementService(
                accessService, mock(WmsInventoryPostingService.class), jdbc, new ObjectMapper());
        when(jdbc.queryForList(contains("WHERE s.tenant_id=? AND s.public_id=?"),
                eq(20L), eq("foreign-shipment"))).thenReturn(List.of());

        assertThrows(WmsResourceNotFoundException.class,
                () -> service.get(principal, "foreign-shipment"));

        verify(jdbc).queryForList(contains("WHERE s.tenant_id=? AND s.public_id=?"),
                eq(20L), eq("foreign-shipment"));
        verifyNoMoreInteractions(jdbc);
    }

    private WmsMembershipAccess access(String permission) {
        return new WmsMembershipAccess(
                30L, "user-public", 10L, "tenant-a", 20L, "membership-a", Set.of(permission));
    }
}
