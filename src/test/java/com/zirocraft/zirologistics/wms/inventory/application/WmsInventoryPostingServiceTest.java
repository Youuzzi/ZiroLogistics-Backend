package com.zirocraft.zirologistics.wms.inventory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsInventoryPostingServiceTest {
    private WmsMembershipAccessService accessService;
    private JdbcTemplate jdbc;
    private WmsInventoryPostingService service;

    @BeforeEach
    void setUp() {
        accessService = mock(WmsMembershipAccessService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new WmsInventoryPostingService(accessService, jdbc, new ObjectMapper());
    }

    @Test
    void deniesPostingWithoutPersistedInventoryPostPermissionBeforeDatabaseAccess() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_INVENTORY_READ")));

        assertThrows(AccessDeniedException.class, () -> service.post(principal(), validCommand()));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsPrincipalWhoseTenantDoesNotMatchPersistedMembership() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_INVENTORY_POST")));
        WmsAuthenticatedPrincipal mismatched = new WmsAuthenticatedPrincipal(
                "user-public", 99L, "other-tenant", 30L, "membership-public", Set.of());

        assertThrows(AccessDeniedException.class, () -> service.post(mismatched, validCommand()));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUnsupportedPairedMovementUntilPairedPostingIsImplemented() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_INVENTORY_POST")));
        var command = new WmsInventoryPostingService.PostingCommand(
                "00000000-0000-0000-0000-000000000001", "TRANSFER",
                validCommand().legs(), null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.post(principal(), command));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsQuantityThatCannotBeRepresentedAtScaleFourBeforeDatabaseAccess() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_INVENTORY_POST")));
        var command = new WmsInventoryPostingService.PostingCommand(
                "00000000-0000-0000-0000-000000000001", "RECEIPT",
                List.of(new WmsInventoryPostingService.LegCommand(
                        "00000000-0000-0000-0000-000000000010",
                        "00000000-0000-0000-0000-000000000020",
                        "00000000-0000-0000-0000-000000000030",
                        null, "AVAILABLE", new BigDecimal("1.00001"), null)),
                null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.post(principal(), command));

        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsDuplicateBalanceBucketsBeforeDatabaseAccess() {
        when(accessService.resolve("user-public", "membership-public"))
                .thenReturn(access(Set.of("WMS_INVENTORY_POST")));
        var leg = validCommand().legs().getFirst();
        var command = new WmsInventoryPostingService.PostingCommand(
                "00000000-0000-0000-0000-000000000001", "RECEIPT",
                List.of(leg, leg), null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.post(principal(), command));

        verifyNoInteractions(jdbc);
    }

    private static WmsInventoryPostingService.PostingCommand validCommand() {
        return new WmsInventoryPostingService.PostingCommand(
                "00000000-0000-0000-0000-000000000001", "RECEIPT",
                List.of(new WmsInventoryPostingService.LegCommand(
                        "00000000-0000-0000-0000-000000000010",
                        "00000000-0000-0000-0000-000000000020",
                        "00000000-0000-0000-0000-000000000030",
                        null, "AVAILABLE", new BigDecimal("3.2500"), null)),
                null, null, null);
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
