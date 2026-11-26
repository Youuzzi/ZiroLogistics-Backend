package com.zirocraft.zirologistics.wms.authorization;

import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccess;
import com.zirocraft.zirologistics.wms.authorization.application.WmsMembershipAccessService;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.WmsMembershipAccessProjection;
import com.zirocraft.zirologistics.wms.authorization.infrastructure.persistence.WmsMembershipAccessRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsMembershipAccessServiceTest {

    private final WmsMembershipAccessRepository repository = mock(WmsMembershipAccessRepository.class);
    private final WmsMembershipAccessService service = new WmsMembershipAccessService(repository);

    @Test
    void combinesDistinctPermissionsReturnedForMultipleAssignedRoles() {
        // The repository query returns one row per distinct permission across all active
        // membership_roles -> roles -> role_permissions relationships.
        when(repository.findActiveAccess("user-1", "membership-1")).thenReturn(List.of(
                row("WMS_INBOUND_RECEIPT_READ"),
                row("WMS_INBOUND_RECEIPT_CREATE"),
                row("WMS_INVENTORY_READ"),
                row("WMS_INVENTORY_READ"), // overlap between two assigned roles
                row("WMS_PICKING_START")));

        WmsMembershipAccess access = service.resolve("user-1", "membership-1");

        assertEquals(4, access.permissionCodes().size());
        assertTrue(access.hasPermission("WMS_INBOUND_RECEIPT_READ"));
        assertTrue(access.hasPermission("WMS_INBOUND_RECEIPT_CREATE"));
        assertTrue(access.hasPermission("WMS_INVENTORY_READ"));
        assertTrue(access.hasPermission("WMS_PICKING_START"));
        assertFalse(access.hasPermission("WMS_INVENTORY_ADJUSTMENT_POST"));
        assertEquals(41L, access.tenantId());
        assertEquals("tenant-1", access.tenantPublicId());
        verify(repository).findActiveAccess("user-1", "membership-1");
    }

    @Test
    void rejectsMembershipWhenNoActivePersistedMembershipRowsExist() {
        when(repository.findActiveAccess("user-1", "revoked-membership")).thenReturn(List.of());

        assertThrows(RuntimeException.class,
                () -> service.resolve("user-1", "revoked-membership"));
    }

    @Test
    void rejectsMissingIdentityBeforeRepositoryAccess() {
        assertThrows(RuntimeException.class, () -> service.resolve(" ", "membership-1"));
        assertThrows(RuntimeException.class, () -> service.resolve("user-1", null));
        verifyNoInteractions(repository);
    }

    private static WmsMembershipAccessProjection row(String permissionCode) {
        WmsMembershipAccessProjection row = mock(WmsMembershipAccessProjection.class);
        when(row.getMembershipId()).thenReturn(51L);
        when(row.getTenantId()).thenReturn(41L);
        when(row.getUserId()).thenReturn(31L);
        when(row.getMembershipPublicId()).thenReturn("membership-1");
        when(row.getTenantPublicId()).thenReturn("tenant-1");
        when(row.getUserPublicId()).thenReturn("user-1");
        when(row.getCredentialVersion()).thenReturn(7L);
        when(row.getPermissionCode()).thenReturn(permissionCode);
        return row;
    }
}
