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
    void exposesUniqueReservedSystemRoleTemplates() {
        var templates = WmsTenantPermissionPolicy.systemRoleTemplates();
        var codes = templates.stream().map(WmsTenantPermissionPolicy.RoleTemplate::code).toList();

        assertEquals(13, templates.size());
        assertEquals(codes.size(), codes.stream().distinct().count());
        assertEquals(templates.size(), WmsTenantPermissionPolicy.reservedSystemRoleCodes().size());
        assertTrue(WmsTenantPermissionPolicy.reservedSystemRoleCodes().contains("SYS_INBOUND_CONTROLLER"));
        assertTrue(WmsTenantPermissionPolicy.reservedSystemRoleCodes().contains("SYS_INVENTORY_APPROVER"));
        assertFalse(WmsTenantPermissionPolicy.reservedSystemRoleCodes().contains("TENANT_OWNER"));
        assertTrue(templates.stream().allMatch(template -> !template.permissionCodes().isEmpty()));
        assertTrue(templates.stream().noneMatch(template -> template.permissionCodes().contains("WMS_INVENTORY_POST")));
    }

    @Test
    void restrictsSelfAssignmentForIndependentCommitAndApprovalRoles() {
        var restricted = WmsTenantPermissionPolicy.selfAssignmentRestrictedSystemRoleCodes();

        assertTrue(restricted.contains("SYS_INBOUND_CONTROLLER"));
        assertTrue(restricted.contains("SYS_OUTBOUND_CONTROLLER"));
        assertTrue(restricted.contains("SYS_SHIPPING_CLERK"));
        assertTrue(restricted.contains("SYS_INVENTORY_APPROVER"));
        assertTrue(restricted.contains("SYS_TRANSFER_DISPATCHER"));
        assertTrue(restricted.contains("SYS_TRANSFER_RECEIVER"));
        assertFalse(restricted.contains("SYS_INBOUND_OPERATOR"));
        assertFalse(restricted.contains("SYS_REPORTING_AUDITOR"));
    }

    @Test
    void policyCollectionsCannotBeMutatedByCallers() {
        assertThrows(UnsupportedOperationException.class,
                () -> WmsTenantPermissionPolicy.tenantOwnerTemplate().add("WMS_INVENTORY_POST"));
        assertThrows(UnsupportedOperationException.class,
                () -> WmsTenantPermissionPolicy.delegablePermissionCodes().add("WMS_INVENTORY_POST"));
        assertThrows(UnsupportedOperationException.class,
                () -> WmsTenantPermissionPolicy.systemRoleTemplates().getFirst()
                        .permissionCodes().add("WMS_INVENTORY_POST"));
    }
}
