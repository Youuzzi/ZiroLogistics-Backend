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

    private static final Set<String> CATALOGUE_VISIBLE_TO_TENANT_ROLE_MANAGERS = Set.of(
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

    private static final List<RoleTemplate> SYSTEM_ROLE_TEMPLATES = List.of(
            new RoleTemplate("SYS_TENANT_ADMINISTRATOR", "Tenant Administrator",
                    "Delegated tenant profile and master-data administration without role governance or inventory posting.",
                    List.of(
                            "WMS_IDENTITY_MEMBERSHIP_INVITE", "WMS_TENANT_PROFILE_UPDATE",
                            "WMS_WAREHOUSE_READ", "WMS_WAREHOUSE_CREATE", "WMS_WAREHOUSE_UPDATE", "WMS_WAREHOUSE_STATUS_MANAGE",
                            "WMS_LOCATION_READ", "WMS_LOCATION_CREATE", "WMS_LOCATION_UPDATE", "WMS_LOCATION_STATUS_MANAGE",
                            "WMS_ITEM_READ", "WMS_ITEM_CREATE", "WMS_ITEM_UPDATE", "WMS_ITEM_STATUS_MANAGE",
                            "WMS_ITEM_CATEGORY_READ", "WMS_ITEM_CATEGORY_MANAGE",
                            "WMS_ITEM_IDENTIFIER_MANAGE", "WMS_ITEM_UOM_CONVERSION_MANAGE", "WMS_UOM_CATALOG_READ",
                            "WMS_LOT_READ", "WMS_LOT_MANAGE", "WMS_SERIAL_READ", "WMS_SERIAL_MANAGE",
                            "WMS_INVENTORY_READ", "WMS_INVENTORY_LEDGER_READ", "WMS_OPERATIONAL_REPORT_READ")),
            new RoleTemplate("SYS_WAREHOUSE_SUPERVISOR", "Warehouse Supervisor",
                    "Coordinates warehouse tasks and assignments without inventory posting or approval authority.",
                    List.of(
                            "WMS_WAREHOUSE_READ", "WMS_LOCATION_READ", "WMS_ITEM_READ",
                            "WMS_INVENTORY_READ", "WMS_INVENTORY_LEDGER_READ",
                            "WMS_INBOUND_RECEIPT_READ", "WMS_PUTAWAY_READ", "WMS_PUTAWAY_ASSIGN",
                            "WMS_OUTBOUND_SHIPMENT_READ", "WMS_PICKING_READ", "WMS_PICKING_ASSIGN",
                            "WMS_PACKING_READ", "WMS_PACKING_ASSIGN", "WMS_SHIPPING_READ",
                            "WMS_TRANSFER_READ", "WMS_STOCK_COUNT_READ", "WMS_INVENTORY_ADJUSTMENT_READ",
                            "WMS_REPLENISHMENT_READ", "WMS_REPLENISHMENT_TASK_RELEASE",
                            "WMS_REPLENISHMENT_TASK_ASSIGN", "WMS_OPERATIONAL_REPORT_READ")),
            new RoleTemplate("SYS_INBOUND_OPERATOR", "Inbound Operator",
                    "Creates and processes inbound receipts and putaway work without receipt posting authority.",
                    List.of(
                            "WMS_INBOUND_RECEIPT_READ", "WMS_INBOUND_RECEIPT_CREATE", "WMS_INBOUND_RECEIPT_UPDATE",
                            "WMS_PUTAWAY_READ", "WMS_PUTAWAY_CREATE", "WMS_PUTAWAY_START", "WMS_PUTAWAY_CONFIRM",
                            "WMS_WAREHOUSE_READ", "WMS_LOCATION_READ", "WMS_ITEM_READ", "WMS_UOM_CATALOG_READ",
                            "WMS_LOT_READ", "WMS_SERIAL_READ", "WMS_INVENTORY_READ", "WMS_INVENTORY_POST")),
            new RoleTemplate("SYS_INBOUND_CONTROLLER", "Inbound Controller",
                    "Independently posts completed inbound receipts and reviews resulting inventory evidence.",
                    List.of(
                            "WMS_INBOUND_RECEIPT_READ", "WMS_INBOUND_RECEIPT_POST", "WMS_INVENTORY_POST",
                            "WMS_INVENTORY_READ", "WMS_INVENTORY_LEDGER_READ",
                            "WMS_WAREHOUSE_READ", "WMS_LOCATION_READ", "WMS_ITEM_READ",
                            "WMS_LOT_READ", "WMS_SERIAL_READ")),
            new RoleTemplate("SYS_OUTBOUND_OPERATOR", "Outbound Operator",
                    "Prepares shipments, reserves inventory, and executes picking and packing without final shipping commit.",
                    List.of(
                            "WMS_OUTBOUND_SHIPMENT_READ", "WMS_OUTBOUND_SHIPMENT_CREATE",
                            "WMS_OUTBOUND_SHIPMENT_UPDATE", "WMS_OUTBOUND_SHIPMENT_ALLOCATE",
                            "WMS_INVENTORY_RESERVE", "WMS_INVENTORY_POST",
                            "WMS_PICKING_READ", "WMS_PICKING_CREATE", "WMS_PICKING_ASSIGN",
                            "WMS_PICKING_START", "WMS_PICKING_CONFIRM",
                            "WMS_PACKING_READ", "WMS_PACKING_CREATE", "WMS_PACKING_READY",
                            "WMS_PACKING_ASSIGN", "WMS_PACKING_START", "WMS_PACKING_PACKAGE",
                            "WMS_PACKING_SEAL", "WMS_PACKING_COMPLETE",
                            "WMS_WAREHOUSE_READ", "WMS_LOCATION_READ", "WMS_ITEM_READ",
                            "WMS_INVENTORY_READ", "WMS_LOT_READ", "WMS_SERIAL_READ")),
            new RoleTemplate("SYS_OUTBOUND_CONTROLLER", "Outbound Controller",
                    "Independently releases allocated outbound shipments before picking and final shipping.",
                    List.of(
                            "WMS_OUTBOUND_SHIPMENT_READ", "WMS_OUTBOUND_SHIPMENT_RELEASE",
                            "WMS_INVENTORY_READ", "WMS_INVENTORY_LEDGER_READ", "WMS_PACKING_READ")),
            new RoleTemplate("SYS_SHIPPING_CLERK", "Shipping Clerk",
                    "Performs final shipping handoff after outbound preparation and packing are complete.",
                    List.of(
                            "WMS_SHIPPING_READ", "WMS_SHIPPING_SHIP", "WMS_INVENTORY_POST",
                            "WMS_OUTBOUND_SHIPMENT_READ", "WMS_PACKING_READ", "WMS_INVENTORY_READ")),
            new RoleTemplate("SYS_INVENTORY_CONTROLLER", "Inventory Controller",
                    "Prepares stock counts and inventory adjustments and executes routine replenishment work.",
                    List.of(
                            "WMS_INVENTORY_READ", "WMS_INVENTORY_LEDGER_READ", "WMS_INVENTORY_POST",
                            "WMS_LOT_READ", "WMS_LOT_MANAGE", "WMS_SERIAL_READ", "WMS_SERIAL_MANAGE",
                            "WMS_STOCK_COUNT_READ", "WMS_STOCK_COUNT_CREATE", "WMS_STOCK_COUNT_PLAN",
                            "WMS_STOCK_COUNT_START", "WMS_STOCK_COUNT_RECORD", "WMS_STOCK_COUNT_SUBMIT",
                            "WMS_STOCK_COUNT_CANCEL",
                            "WMS_INVENTORY_ADJUSTMENT_READ", "WMS_INVENTORY_ADJUSTMENT_CREATE",
                            "WMS_INVENTORY_ADJUSTMENT_SUBMIT",
                            "WMS_REPLENISHMENT_READ", "WMS_REPLENISHMENT_RULE_MANAGE",
                            "WMS_REPLENISHMENT_TASK_CREATE", "WMS_REPLENISHMENT_TASK_RELEASE",
                            "WMS_REPLENISHMENT_TASK_ASSIGN", "WMS_REPLENISHMENT_TASK_START",
                            "WMS_REPLENISHMENT_TASK_CONFIRM", "WMS_REPLENISHMENT_TASK_CANCEL",
                            "WMS_WAREHOUSE_READ", "WMS_LOCATION_READ", "WMS_ITEM_READ")),
            new RoleTemplate("SYS_INVENTORY_APPROVER", "Inventory Approver",
                    "Independently reviews and approves stock-count variances and inventory adjustments.",
                    List.of(
                            "WMS_STOCK_COUNT_READ", "WMS_STOCK_COUNT_REVIEW", "WMS_STOCK_COUNT_APPROVE",
                            "WMS_STOCK_COUNT_CANCEL",
                            "WMS_INVENTORY_ADJUSTMENT_READ", "WMS_INVENTORY_ADJUSTMENT_APPROVE",
                            "WMS_INVENTORY_ADJUSTMENT_REJECT", "WMS_INVENTORY_ADJUSTMENT_POST",
                            "WMS_INVENTORY_POST", "WMS_INVENTORY_READ", "WMS_INVENTORY_LEDGER_READ")),
            new RoleTemplate("SYS_TRANSFER_DISPATCHER", "Transfer Dispatcher",
                    "Creates, releases, and dispatches inter-warehouse transfers from the source location.",
                    List.of(
                            "WMS_TRANSFER_READ", "WMS_TRANSFER_CREATE", "WMS_TRANSFER_RELEASE",
                            "WMS_TRANSFER_DISPATCH", "WMS_INVENTORY_POST", "WMS_WAREHOUSE_READ", "WMS_LOCATION_READ",
                            "WMS_ITEM_READ", "WMS_INVENTORY_READ", "WMS_LOT_READ", "WMS_SERIAL_READ")),
            new RoleTemplate("SYS_TRANSFER_RECEIVER", "Transfer Receiver",
                    "Receives dispatched inter-warehouse transfers at the destination location.",
                    List.of(
                            "WMS_TRANSFER_READ", "WMS_TRANSFER_RECEIVE", "WMS_INVENTORY_POST", "WMS_WAREHOUSE_READ",
                            "WMS_LOCATION_READ", "WMS_ITEM_READ", "WMS_INVENTORY_READ",
                            "WMS_LOT_READ", "WMS_SERIAL_READ")),
            new RoleTemplate("SYS_REPLENISHMENT_OPERATOR", "Replenishment Operator",
                    "Executes replenishment tasks without permission to reverse completed confirmations.",
                    List.of(
                            "WMS_REPLENISHMENT_READ", "WMS_REPLENISHMENT_TASK_CREATE",
                            "WMS_REPLENISHMENT_TASK_RELEASE", "WMS_REPLENISHMENT_TASK_ASSIGN",
                            "WMS_REPLENISHMENT_TASK_START", "WMS_REPLENISHMENT_TASK_CONFIRM",
                            "WMS_REPLENISHMENT_TASK_CANCEL", "WMS_INVENTORY_POST",
                            "WMS_INVENTORY_READ", "WMS_WAREHOUSE_READ", "WMS_LOCATION_READ",
                            "WMS_ITEM_READ", "WMS_LOT_READ", "WMS_SERIAL_READ")),
            new RoleTemplate("SYS_REPORTING_AUDITOR", "Reporting Auditor",
                    "Read-only access to tenant operational reports and workflow evidence.",
                    List.of(
                            "WMS_OPERATIONAL_REPORT_READ", "WMS_WAREHOUSE_READ", "WMS_LOCATION_READ",
                            "WMS_ITEM_READ", "WMS_LOT_READ", "WMS_SERIAL_READ",
                            "WMS_INVENTORY_READ", "WMS_INVENTORY_LEDGER_READ",
                            "WMS_INBOUND_RECEIPT_READ", "WMS_PUTAWAY_READ",
                            "WMS_OUTBOUND_SHIPMENT_READ", "WMS_PICKING_READ", "WMS_PACKING_READ",
                            "WMS_SHIPPING_READ", "WMS_TRANSFER_READ", "WMS_STOCK_COUNT_READ",
                            "WMS_INVENTORY_ADJUSTMENT_READ", "WMS_REPLENISHMENT_READ"))
    );

    private WmsTenantPermissionPolicy() {
    }

    /** Exact least-privilege baseline for newly created TENANT_OWNER system roles. */
    public static List<String> tenantOwnerTemplate() {
        return TENANT_OWNER_TEMPLATE;
    }

    /** Default operational role templates available to every tenant. */
    public static List<RoleTemplate> systemRoleTemplates() {
        return SYSTEM_ROLE_TEMPLATES;
    }

    /** Codes reserved for server-managed tenant system roles. */
    public static Set<String> reservedSystemRoleCodes() {
        return SYSTEM_ROLE_TEMPLATES.stream().map(RoleTemplate::code)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Codes that a tenant role manager may expose in the delegable permission catalogue. */
    public static Set<String> catalogueVisiblePermissionCodes() {
        return CATALOGUE_VISIBLE_TO_TENANT_ROLE_MANAGERS;
    }

    /** Codes eligible for assignment to custom tenant roles, still bounded by caller authority. */
    public static Set<String> delegablePermissionCodes() {
        return DELEGABLE_TO_CUSTOM_ROLES;
    }
}
