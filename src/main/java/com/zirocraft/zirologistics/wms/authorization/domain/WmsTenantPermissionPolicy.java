package com.zirocraft.zirologistics.wms.authorization.domain;

import java.util.List;
import java.util.Set;

/**
 * Canonical tenant WMS permission policy shared by provisioning and authorization.
 *
 * <p>The permission catalogue is global, while visibility, delegation, and system-role
 * grants are separate policy decisions. Platform control-plane permissions do not belong
 * in this tenant policy.</p>
 */
public final class WmsTenantPermissionPolicy {
    private static final List<String> TENANT_OWNER_TEMPLATE = List.of(
            "WMS_IDENTITY_MEMBERSHIP_INVITE",
            "WMS_TENANT_PROFILE_UPDATE",
            "WMS_ROLE_READ",
            "WMS_ROLE_CREATE",
            "WMS_ROLE_UPDATE",
            "WMS_ROLE_DEACTIVATE",
            "WMS_PERMISSION_CATALOG_READ",
            "WMS_ROLE_PERMISSION_MANAGE",
            "WMS_MEMBERSHIP_ROLE_READ",
            "WMS_MEMBERSHIP_ROLE_MANAGE",
            "WMS_WAREHOUSE_READ",
            "WMS_LOCATION_READ",
            "WMS_ITEM_READ",
            "WMS_ITEM_CATEGORY_READ",
            "WMS_UOM_CATALOG_READ",
            "WMS_LOT_READ",
            "WMS_SERIAL_READ",
            "WMS_INVENTORY_READ",
            "WMS_INVENTORY_LEDGER_READ",
            "WMS_INBOUND_RECEIPT_READ",
            "WMS_PUTAWAY_READ",
            "WMS_OUTBOUND_SHIPMENT_READ",
            "WMS_PICKING_READ",
            "WMS_PACKING_READ",
            "WMS_SHIPPING_READ",
            "WMS_TRANSFER_READ",
            "WMS_STOCK_COUNT_READ",
            "WMS_INVENTORY_ADJUSTMENT_READ",
            "WMS_REPLENISHMENT_READ",
            "WMS_OPERATIONAL_REPORT_READ"
    );

    private static final Set<String> DELEGABLE_TO_CUSTOM_ROLES = Set.of(
            "WMS_ROLE_READ",
            "WMS_ROLE_CREATE",
            "WMS_ROLE_UPDATE",
            "WMS_ROLE_DEACTIVATE",
            "WMS_PERMISSION_CATALOG_READ",
            "WMS_MEMBERSHIP_ROLE_READ",
            "WMS_WAREHOUSE_READ",
            "WMS_WAREHOUSE_CREATE",
            "WMS_WAREHOUSE_UPDATE",
            "WMS_LOCATION_READ",
            "WMS_LOCATION_CREATE",
            "WMS_LOCATION_UPDATE",
            "WMS_ITEM_READ",
            "WMS_ITEM_CREATE",
            "WMS_ITEM_UPDATE",
            "WMS_ITEM_CATEGORY_READ",
            "WMS_ITEM_CATEGORY_MANAGE",
            "WMS_UOM_CATALOG_READ",
            "WMS_INVENTORY_READ",
            "WMS_INVENTORY_LEDGER_READ"
    );

    private WmsTenantPermissionPolicy() {
    }

    /** Exact least-privilege baseline for newly created TENANT_OWNER system roles. */
    public static List<String> tenantOwnerTemplate() {
        return TENANT_OWNER_TEMPLATE;
    }

    /** Codes that a tenant role manager may expose in the delegable permission catalogue. */
    public static Set<String> catalogueVisiblePermissionCodes() {
        return DELEGABLE_TO_CUSTOM_ROLES;
    }

    /** Codes eligible for assignment to custom tenant roles, still bounded by caller authority. */
    public static Set<String> delegablePermissionCodes() {
        return DELEGABLE_TO_CUSTOM_ROLES;
    }
}
