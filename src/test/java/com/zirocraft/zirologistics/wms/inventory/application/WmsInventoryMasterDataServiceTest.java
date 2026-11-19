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


    @Test
    void itemWeightAndMassUomMustBeSuppliedTogetherBeforeUomResolution() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ITEM_CREATE")));
        when(jdbc.queryForList(contains("FROM tenants"), eq(Long.class), eq(10L)))
                .thenReturn(List.of(10L));

        WmsInventoryMasterDataService.ItemCommand command = new WmsInventoryMasterDataService.ItemCommand(
                "SKU-WEIGHT", "Weighted Item", null, null, "EA", "NONE",
                true, true, true, java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("1.250000"), null);

        assertThrows(IllegalArgumentException.class, () -> service.createItem(principal(), command));

        verify(jdbc).queryForList(contains("FROM tenants"), eq(Long.class), eq(10L));
        verify(jdbc, never()).queryForList(contains("FROM units_of_measure"), eq(Long.class), any());
    }

    @Test
    void itemWeightMustFitDatabasePrecisionBeforeUomResolution() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ITEM_CREATE")));
        when(jdbc.queryForList(contains("FROM tenants"), eq(Long.class), eq(10L)))
                .thenReturn(List.of(10L));

        WmsInventoryMasterDataService.ItemCommand command = new WmsInventoryMasterDataService.ItemCommand(
                "SKU-WEIGHT", "Weighted Item", null, null, "EA", "NONE",
                true, true, true, java.math.BigDecimal.ZERO,
                new java.math.BigDecimal("1.1234567"), "KG");

        assertThrows(IllegalArgumentException.class, () -> service.createItem(principal(), command));

        verify(jdbc).queryForList(contains("FROM tenants"), eq(Long.class), eq(10L));
        verify(jdbc, never()).queryForList(contains("FROM units_of_measure"), eq(Long.class), any());
    }

    @Test
    void identifierLookupPreservesLeadingZeroesAndIsTenantScoped() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ITEM_READ")));
        when(jdbc.queryForList(anyString(), eq(10L), eq("GTIN"), eq("00012345678905")))
                .thenReturn(List.of(java.util.Map.of("public_id", "item-public", "sku", "SKU-1",
                        "item_name", "Item", "status", "ACTIVE", "tracking_method", "NONE",
                        "is_stock_item", 1, "base_uom_code", "EA")));

        var result = service.lookupItemByIdentifier(principal(), "gtin", "00012345678905");

        assertEquals("item-public", result.get("public_id"));
        verify(jdbc).queryForList(contains("WHERE x.tenant_id = ?"), eq(10L),
                eq("GTIN"), eq("00012345678905"));
    }

    @Test
    void identifierLookupRejectsMalformedGtinBeforeQuery() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ITEM_READ")));

        assertThrows(IllegalArgumentException.class,
                () -> service.lookupItemByIdentifier(principal(), "GTIN", "12345"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void conversionFactorMustBePositiveAndWithinDatabasePrecision() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_ITEM_UOM_CONVERSION_MANAGE")));

        assertThrows(IllegalArgumentException.class, () -> service.addUomConversion(
                principal(), "item-public",
                new WmsInventoryMasterDataService.UomConversionCommand("EA", "CASE", new java.math.BigDecimal("0"))));

        verifyNoInteractions(jdbc);
    }


    @Test
    void lotDateValidationRejectsExpiryBeforeManufactureBeforeDatabaseAccess() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_LOT_MANAGE")));

        assertThrows(IllegalArgumentException.class, () -> service.createLot(principal(), "item-public",
                new WmsInventoryMasterDataService.LotCommand("LOT-1", null,
                        java.time.LocalDate.of(2026, 5, 10), null,
                        java.time.LocalDate.of(2026, 5, 9), null)));

        verifyNoInteractions(jdbc);
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
