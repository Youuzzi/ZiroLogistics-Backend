package com.zirocraft.zirologistics.wms.warehouse.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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

        assertThrows(WmsMembershipAccessDeniedException.class, () -> service.listWarehouses(principal));

        verifyNoInteractions(jdbc);
    }

    @Test
    void getLocationCannotResolveForeignTenantPublicIdInsideOwnWarehouse() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_LOCATION_READ")));
        when(jdbc.queryForList(contains("FROM warehouses WHERE tenant_id = ? AND public_id = ?"),
                eq(10L), eq("tenant-a-warehouse")))
                .thenReturn(List.of(Map.of("id", 501L, "public_id", "tenant-a-warehouse",
                        "tenant_id", 10L, "warehouse_code", "A-01", "warehouse_name", "Tenant A",
                        "status", "ACTIVE", "version", 0L)));
        when(jdbc.queryForList(contains("FROM warehouse_locations l"),
                eq(10L), eq(501L), eq("tenant-b-location"))).thenReturn(List.of());

        assertThrows(WmsWarehouseNotFoundException.class,
                () -> service.getLocation(principal, "tenant-a-warehouse", "tenant-b-location"));

        verify(jdbc).queryForList(contains("WHERE l.tenant_id = ? AND l.warehouse_id = ? AND l.public_id = ?"),
                eq(10L), eq(501L), eq("tenant-b-location"));
    }

    @Test
    void createLocationRejectsParentLocationOwnedByAnotherTenant() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_LOCATION_CREATE")));
        when(jdbc.queryForList(contains("FROM tenants WHERE id = ?"), eq(Long.class), eq(10L)))
                .thenReturn(List.of(10L));
        when(jdbc.queryForList(contains("FROM warehouses WHERE tenant_id = ? AND public_id = ?"),
                eq(10L), eq("tenant-a-warehouse")))
                .thenReturn(List.of(Map.of("id", 501L, "public_id", "tenant-a-warehouse",
                        "tenant_id", 10L, "warehouse_code", "A-01", "warehouse_name", "Tenant A",
                        "status", "ACTIVE", "version", 0L)));
        when(jdbc.queryForList(contains("FROM warehouse_locations"),
                eq(10L), eq(501L), eq("tenant-b-parent"))).thenReturn(List.of());

        WmsWarehouseManagementService.LocationCommand command =
                new WmsWarehouseManagementService.LocationCommand(
                        "BIN-NEW", "New Bin", "BIN", null, "tenant-b-parent",
                        null, null, null, null, true, false, false, false, 10, 10);

        assertThrows(WmsWarehouseNotFoundException.class,
                () -> service.createLocation(principal, "tenant-a-warehouse", command));

        verify(jdbc).queryForList(contains("WHERE tenant_id = ? AND warehouse_id = ? AND public_id = ?"),
                eq(10L), eq(501L), eq("tenant-b-parent"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
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

        assertThrows(WmsMembershipAccessDeniedException.class, () -> service.getWarehouse(forged, "warehouse-b-public"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void warehouseLookupUsesTenantScopedPublicIdQuery() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_WAREHOUSE_READ")));
        when(jdbc.queryForList(anyString(), eq(10L), eq("warehouse-b-public")))
                .thenReturn(List.of());

        assertThrows(WmsWarehouseNotFoundException.class, () -> service.getWarehouse(principal, "warehouse-b-public"));

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
