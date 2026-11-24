package com.zirocraft.zirologistics.wms.authorization;

import com.zirocraft.zirologistics.wms.authorization.domain.WmsTenantPermissionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WmsTenantPermissionPolicyTest {

    @Test
    void tenantOwnerTemplateContainsGovernanceAndReadOnlyOperationalAccess() {
        var permissions = WmsTenantPermissionPolicy.tenantOwnerTemplate();

        assertTrue(permissions.contains("WMS_TENANT_PROFILE_UPDATE"));
        assertTrue(permissions.contains("WMS_ROLE_PERMISSION_MANAGE"));
        assertTrue(permissions.contains("WMS_MEMBERSHIP_ROLE_MANAGE"));
        assertTrue(permissions.contains("WMS_INVENTORY_READ"));
        assertTrue(permissions.contains("WMS_OPERATIONAL_REPORT_READ"));
        assertFalse(permissions.contains("WMS_INVENTORY_POST"));
        assertFalse(permissions.contains("WMS_INVENTORY_RESERVE"));
        assertFalse(permissions.contains("WMS_STOCK_COUNT_APPROVE"));
        assertFalse(permissions.contains("WMS_INVENTORY_ADJUSTMENT_POST"));
        assertFalse(permissions.contains("WMS_SHIPPING_SHIP"));
    }

    @Test
    void customRoleDelegationRemainsNarrowAndExcludesHighRiskPermissions() {
        var delegable = WmsTenantPermissionPolicy.delegablePermissionCodes();

        assertTrue(delegable.contains("WMS_ROLE_READ"));
        assertTrue(delegable.contains("WMS_WAREHOUSE_READ"));
        assertTrue(delegable.contains("WMS_ITEM_CREATE"));
        assertFalse(delegable.contains("WMS_ROLE_PERMISSION_MANAGE"));
        assertFalse(delegable.contains("WMS_MEMBERSHIP_ROLE_MANAGE"));
        assertFalse(delegable.contains("WMS_INVENTORY_POST"));
        assertFalse(delegable.contains("WMS_STOCK_COUNT_APPROVE"));
        assertFalse(delegable.contains("WMS_INVENTORY_ADJUSTMENT_POST"));
        assertEquals(delegable, WmsTenantPermissionPolicy.catalogueVisiblePermissionCodes());
    }

    @Test
    void policyCollectionsCannotBeMutatedByCallers() {
        assertThrows(UnsupportedOperationException.class,
                () -> WmsTenantPermissionPolicy.tenantOwnerTemplate().add("WMS_INVENTORY_POST"));
        assertThrows(UnsupportedOperationException.class,
                () -> WmsTenantPermissionPolicy.delegablePermissionCodes().add("WMS_INVENTORY_POST"));
    }
}
