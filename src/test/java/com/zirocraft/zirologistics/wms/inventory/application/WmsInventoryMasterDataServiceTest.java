package com.zirocraft.zirologistics.wms.inventory.application;

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

class WmsInventoryMasterDataServiceTest {
    private WmsMembershipAccessService accessService;
    private JdbcTemplate jdbc;
    private WmsInventoryMasterDataService service;

    @BeforeEach
    void setUp() {
        accessService = mock(WmsMembershipAccessService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new WmsInventoryMasterDataService(accessService, jdbc, new ObjectMapper());
    }

    @Test
    void deniesUomReadWithoutPersistedPermissionBeforeDatabaseQuery() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ITEM_READ")));

        assertThrows(AccessDeniedException.class, () -> service.listUoms(principal(), null));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsPrincipalWhoseTenantDoesNotMatchPersistedMembership() {
        WmsAuthenticatedPrincipal mismatched = new WmsAuthenticatedPrincipal(
                "user-public", 99L, "other-tenant", 30L, "membership-public", Set.of("WMS_ITEM_READ"));
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ITEM_READ")));

        assertThrows(AccessDeniedException.class, () -> service.listItems(mismatched));

        verifyNoInteractions(jdbc);
    }

    @Test
    void readsGlobalUomCatalogueOnlyAfterPersistedPermissionResolution() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_UOM_CATALOG_READ")));
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        assertTrue(service.listUoms(principal(), null).isEmpty());

        verify(jdbc).queryForList(contains("FROM units_of_measure WHERE is_active = 1"));
    }

    @Test
    void filtersGlobalUomCatalogueByValidatedDimension() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_UOM_CATALOG_READ")));
        when(jdbc.queryForList(anyString(), eq("COUNT"))).thenReturn(List.of());

        assertTrue(service.listUoms(principal(), "count").isEmpty());

        verify(jdbc).queryForList(contains("dimension = ?"), eq("COUNT"));
        assertThrows(IllegalArgumentException.class, () -> service.listUoms(principal(), "CURRENCY"));
    }

    @Test
    void listsItemsOnlyInsideResolvedTenant() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ITEM_READ")));
        when(jdbc.queryForList(anyString(), eq(10L))).thenReturn(List.of());

        assertTrue(service.listItems(principal()).isEmpty());

        verify(jdbc).queryForList(contains("WHERE i.tenant_id = ?"), eq(10L));
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
