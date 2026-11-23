package com.zirocraft.zirologistics.wms.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.transfer.application.WmsInterWarehouseTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WmsInterWarehouseTransferServiceTest {
    private WmsMembershipAccessService accessService;
    private JdbcTemplate jdbc;
    private WmsInterWarehouseTransferService service;

    @BeforeEach
    void setUp() {
        accessService = mock(WmsMembershipAccessService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new WmsInterWarehouseTransferService(
                accessService, mock(WmsInventoryPostingService.class), jdbc, new ObjectMapper());
    }

    @Test
    void appliesDeterministicLimitAndOffsetForRequestedTransferPage() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_TRANSFER_READ")));

        service.list(principal(), 2, 50);

        verify(jdbc).queryForList(contains("LIMIT ? OFFSET ?"), eq(10L), eq(50), eq(100L));
    }

    @Test
    void rejectsNegativeTransferPageBeforeExecutingCollectionQuery() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_TRANSFER_READ")));

        assertThrows(IllegalArgumentException.class, () -> service.list(principal(), -1, 50));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsTransferPageSizeAboveMaximumBeforeExecutingCollectionQuery() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_TRANSFER_READ")));

        assertThrows(IllegalArgumentException.class, () -> service.list(principal(), 0, 201));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsTransferOffsetBeyondSupportedRangeBeforeExecutingCollectionQuery() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_TRANSFER_READ")));

        assertThrows(IllegalArgumentException.class,
                () -> service.list(principal(), Integer.MAX_VALUE, 200));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsResolvedMembershipFromAnotherTenantBeforeExecutingTransferQuery() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(new WmsMembershipAccess(
                        1L, "user-public", 10L, "other-tenant-public", 99L, "membership-public",
                        Set.of("WMS_TRANSFER_READ")));

        assertThrows(AccessDeniedException.class, () -> service.list(principal()));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsResolvedMembershipWithDifferentMembershipIdentity() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(new WmsMembershipAccess(
                        2L, "user-public", 10L, "tenant-public", 30L, "other-membership-public",
                        Set.of("WMS_TRANSFER_READ")));

        assertThrows(AccessDeniedException.class, () -> service.list(principal()));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMissingTransferReadPermissionBeforeExecutingQuery() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of()));

        assertThrows(AccessDeniedException.class, () -> service.list(principal()));

        verifyNoInteractions(jdbc);
    }

    private static WmsAuthenticatedPrincipal principal() {
        return new WmsAuthenticatedPrincipal(
                "user-public", 10L, "tenant-public", 30L, "membership-public",
                Set.of("WMS_TRANSFER_READ"));
    }

    private static WmsMembershipAccess access(Set<String> permissions) {
        return new WmsMembershipAccess(
                1L, "user-public", 10L, "tenant-public", 30L, "membership-public", permissions);
    }
}