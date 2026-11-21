package com.zirocraft.zirologistics.wms.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.transfer.application.WmsInterWarehouseTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WmsInterWarehouseTransferTenantBoundaryTest {
    private WmsMembershipAccessService accessService;
    private JdbcTemplate jdbc;
    private WmsInterWarehouseTransferService service;
    private WmsAuthenticatedPrincipal principal;

    @BeforeEach
    void setUp() {
        accessService = mock(WmsMembershipAccessService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new WmsInterWarehouseTransferService(
                accessService, mock(WmsInventoryPostingService.class), jdbc, new ObjectMapper());
        principal = new WmsAuthenticatedPrincipal(
                "user-public", 20L, "tenant-a", 30L, "membership-a", Set.of());
        when(accessService.resolve("user-public", "membership-a")).thenReturn(
                new WmsMembershipAccess(30L, "user-public", 20L, "tenant-a", 30L,
                        "membership-a", Set.of("WMS_TRANSFER_READ")));
    }

    @Test
    void foreignTransferPublicIdIsNotFoundWithinTheAuthenticatedTenantScope() {
        when(jdbc.queryForList(contains("WHERE t.tenant_id=? AND t.public_id=?"), eq(20L), eq("foreign-transfer")))
                .thenReturn(List.of());

        assertThrows(WmsResourceNotFoundException.class,
                () -> service.get(principal, "foreign-transfer"));

        verify(jdbc).queryForList(contains("WHERE t.tenant_id=? AND t.public_id=?"),
                eq(20L), eq("foreign-transfer"));
        verifyNoMoreInteractions(jdbc);
    }
}
