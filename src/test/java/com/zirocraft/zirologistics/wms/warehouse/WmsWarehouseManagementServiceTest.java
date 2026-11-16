package com.zirocraft.zirologistics.wms.warehouse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessDeniedException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.warehouse.application.WmsWarehouseManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
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
    void deniesWarehouseReadWhenPersistedMembershipLacksPermission() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_LOCATION_READ")));

        assertThrows(AccessDeniedException.class, () -> service.listWarehouses(principal));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMismatchedTenantBeforeAnyWarehouseQuery() {
        WmsAuthenticatedPrincipal principal = new WmsAuthenticatedPrincipal(
                "user-public", 99L, "other-tenant", 30L, "membership-public",
                Set.of("WMS_WAREHOUSE_READ"));
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_WAREHOUSE_READ")));

        assertThrows(WmsMembershipAccessDeniedException.class,
                () -> service.listWarehouses(principal));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsInvalidWarehouseCodeBeforeInsert() {
        WmsAuthenticatedPrincipal principal = principal();
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_WAREHOUSE_CREATE")));
        when(jdbc.queryForList(
                eq("SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE"),
                eq(Long.class), eq(10L))).thenReturn(List.of(10L));

        var invalid = new WmsWarehouseManagementService.WarehouseCommand(
                " ", "Main Warehouse", null, null, null, null, null,
                "ID", null, null, null, "Asia/Jakarta");

        assertThrows(IllegalArgumentException.class,
                () -> service.createWarehouse(principal, invalid));

        verify(jdbc, times(1)).queryForList(
                eq("SELECT id FROM tenants WHERE id = ? AND status = 'ACTIVE' FOR UPDATE"),
                eq(Long.class), eq(10L));
        verifyNoMoreInteractions(jdbc);
    }

    private static WmsAuthenticatedPrincipal principal() {
        return new WmsAuthenticatedPrincipal(
                "user-public", 10L, "tenant-public", 30L, "membership-public", Set.of());
    }

    private static WmsMembershipAccess access(Set<String> permissions) {
        return new WmsMembershipAccess(
                1L, "user-public", 10L, "tenant-public", 30L, "membership-public", permissions);
    }
}
