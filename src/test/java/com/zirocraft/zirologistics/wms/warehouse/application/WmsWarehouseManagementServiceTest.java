package com.zirocraft.zirologistics.wms.warehouse.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsWarehouseManagementServiceTest {
    private WmsMembershipAccessService accessService;
    private JdbcTemplate jdbc;
    private WmsWarehouseManagementService service;

    @BeforeEach
    void setUp() {
        accessService = mock(WmsMembershipAccessService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new WmsWarehouseManagementService(accessService, jdbc, new ObjectMapper());
    }

    @Test
    void deniesWarehouseReadWithoutPersistedPermissionBeforeDatabaseQuery() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ROLE_READ")));

        assertThrows(AccessDeniedException.class, () -> service.listWarehouses(principal));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsPrincipalWhoseTenantDoesNotMatchPersistedMembership() {
        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                "user-public", 99L, "other-tenant", 30L, "membership-public", Set.of("WMS_WAREHOUSE_READ"));
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_WAREHOUSE_READ")));

        assertThrows(RuntimeException.class, () -> service.listWarehouses(principal));

        verifyNoInteractions(jdbc);
    }

    @Test
    void listsOnlyWarehousesInsideResolvedTenant() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_WAREHOUSE_READ")));
        when(jdbc.queryForList(anyString(), eq(10L))).thenReturn(List.of());

        assertTrue(service.listWarehouses(principal).isEmpty());

        verify(jdbc).queryForList(contains("WHERE tenant_id = ?"), eq(10L));
    }


    @Test
    void rejectsForgedTenantContextBeforeAttemptingWarehouseRead() {
        WmsAuthenticatedPrincipal forged = new WmsAuthenticatedPrincipal(
                "user-public", 99L, "other-tenant", 30L, "membership-public",
                Set.of("WMS_WAREHOUSE_READ"));
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_WAREHOUSE_READ")));

        assertThrows(RuntimeException.class, () -> service.getWarehouse(forged, "warehouse-b-public"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void warehouseLookupUsesTenantScopedPublicIdQuery() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_WAREHOUSE_READ")));
        when(jdbc.queryForList(anyString(), eq(10L), eq("warehouse-b-public")))
                .thenReturn(List.of());

        assertThrows(RuntimeException.class, () -> service.getWarehouse(principal, "warehouse-b-public"));

        verify(jdbc).queryForList(contains("FROM warehouses WHERE tenant_id = ? AND public_id = ?"),
                eq(10L), eq("warehouse-b-public"));
    }

    private static WmsAuthenticatedPrincipal principal() {
        return new WmsAuthenticatedPrincipal(
                "user-public", 10L, "tenant-public", 30L, "membership-public",
                Set.of("WMS_WAREHOUSE_READ"));
    }

    private static WmsMembershipAccess access(Set<String> permissions) {
        return new WmsMembershipAccess(
                1L, "user-public", 10L, "tenant-public", 30L, "membership-public", permissions);
    }
}
