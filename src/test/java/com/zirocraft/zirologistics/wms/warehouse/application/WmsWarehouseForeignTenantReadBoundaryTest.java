package com.zirocraft.zirologistics.wms.warehouse.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsWarehouseForeignTenantReadBoundaryTest {
    @Test
    void foreignWarehousePublicIdIsNotResolvedOutsideAuthenticatedTenant() {
        WmsMembershipAccessService accessService = mock(WmsMembershipAccessService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WmsWarehouseManagementService service =
                new WmsWarehouseManagementService(accessService, jdbc, new ObjectMapper());
        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                "user-public", 10L, "tenant-a", 30L, "membership-public", Set.of());
        when(accessService.resolve("user-public", "membership-public")).thenReturn(
                new WmsMembershipAccess(1L, "user-public", 10L, "tenant-a", 30L,
                        "membership-public", Set.of("WMS_WAREHOUSE_READ")));
        when(jdbc.queryForList(contains("WHERE tenant_id = ? AND public_id = ?"),
                eq(10L), eq("foreign-warehouse"))).thenReturn(List.of());

        assertThrows(WmsWarehouseNotFoundException.class,
                () -> service.getWarehouse(principal, "foreign-warehouse"));

        verify(jdbc).queryForList(contains("WHERE tenant_id = ? AND public_id = ?"),
                eq(10L), eq("foreign-warehouse"));
        verifyNoMoreInteractions(jdbc);
    }
}
