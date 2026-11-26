package com.zirocraft.zirologistics.wms.authorization;

import com.zirocraft.zirologistics.wms.authorization.domain.WmsTenantPermissionPolicy;
import com.zirocraft.zirologistics.wms.authorization.domain.WmsTenantPermissionRiskRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WmsTenantPermissionRiskRegistryTest {

    @Test
    void everyRegistryEntryHasCompleteConsistentRiskMetadata() {
        var entries = WmsTenantPermissionRiskRegistry.all();
        assertEquals(94, entries.size());
        assertEquals(entries.size(), entries.stream().map(e -> e.code()).distinct().count());
        assertTrue(entries.stream().allMatch(e -> !e.module().isBlank() && !e.description().isBlank()));
        assertTrue(entries.stream().allMatch(e ->
                e.tenantAssignable() == WmsTenantPermissionPolicy.delegablePermissionCodes().contains(e.code())));
        assertTrue(entries.stream().filter(e -> e.riskClass() == WmsTenantPermissionRiskRegistry.RiskClass.C1
                        || e.riskClass() == WmsTenantPermissionRiskRegistry.RiskClass.A1)
                .allMatch(e -> e.freshMfaRequired() && e.auditRequired()));
        assertTrue(entries.stream().filter(e -> !e.code().endsWith("_READ")
                        && !e.code().endsWith("_CATALOG_READ"))
                .allMatch(WmsTenantPermissionRiskRegistry.Metadata::auditRequired));
        assertTrue(entries.stream().filter(e -> e.code().endsWith("_READ")
                        || e.code().endsWith("_CATALOG_READ"))
                .noneMatch(WmsTenantPermissionRiskRegistry.Metadata::freshMfaRequired));
    }

    @Test
    void criticalWorkflowPermissionsCarryTheirIndependentActorGroup() {
        assertEquals("INBOUND_RECEIPT",
                WmsTenantPermissionRiskRegistry.require("WMS_INBOUND_RECEIPT_POST").separationOfDutiesGroup());
        assertEquals("OUTBOUND_SHIPMENT",
                WmsTenantPermissionRiskRegistry.require("WMS_SHIPPING_SHIP").separationOfDutiesGroup());
        assertEquals("INTER_WAREHOUSE_TRANSFER",
                WmsTenantPermissionRiskRegistry.require("WMS_TRANSFER_RECEIVE").separationOfDutiesGroup());
        assertEquals("STOCK_COUNT",
                WmsTenantPermissionRiskRegistry.require("WMS_STOCK_COUNT_APPROVE").separationOfDutiesGroup());
        assertEquals("INVENTORY_ADJUSTMENT",
                WmsTenantPermissionRiskRegistry.require("WMS_INVENTORY_ADJUSTMENT_POST").separationOfDutiesGroup());
    }

    @Test
    void unknownPermissionsFailClosedAndRegistryCollectionsAreImmutable() {
        assertTrue(WmsTenantPermissionRiskRegistry.find("WMS_UNKNOWN_PERMISSION").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> WmsTenantPermissionRiskRegistry.require("WMS_UNKNOWN_PERMISSION"));
        assertThrows(UnsupportedOperationException.class,
                () -> WmsTenantPermissionRiskRegistry.permissionCodes().add("WMS_UNKNOWN_PERMISSION"));
        assertThrows(UnsupportedOperationException.class,
                () -> WmsTenantPermissionRiskRegistry.all().clear());
    }

    @Test
    void tenantAssignableSetIsAnExplicitSubsetOfTheRegistry() {
        Set<String> assignable = WmsTenantPermissionRiskRegistry.tenantAssignablePermissionCodes();
        assertEquals(WmsTenantPermissionPolicy.delegablePermissionCodes(), assignable);
        assertFalse(assignable.contains("WMS_INVENTORY_POST"));
        assertFalse(assignable.contains("WMS_TRANSFER_RECEIVE"));
        assertFalse(assignable.contains("WMS_INVENTORY_ADJUSTMENT_APPROVE"));
    }
}
