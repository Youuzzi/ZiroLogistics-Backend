package com.zirocraft.zirologistics.wms.authorization.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable risk metadata for the tenant WMS permission catalogue.
 * This metadata never grants a permission; authorization remains exact-code RBAC.
 */
public final class WmsTenantPermissionRiskRegistry {
    public enum RiskClass { G1, M1, R1, O1, C1, A1 }

    public record Metadata(String code, String module, RiskClass riskClass,
                           boolean tenantAssignable, boolean freshMfaRequired,
                           boolean auditRequired, String separationOfDutiesGroup,
                           String description) {
        public Metadata {
            Objects.requireNonNull(code);
            Objects.requireNonNull(module);
            Objects.requireNonNull(riskClass);
            Objects.requireNonNull(description);
            if (code.isBlank() || module.isBlank() || description.isBlank()) {
                throw new IllegalArgumentException("Permission risk metadata fields must be nonblank");
            }
        }
    }

    private static final Map<String, Metadata> ENTRIES = build();

    private WmsTenantPermissionRiskRegistry() { }

    private static Metadata entry(String code, String module, RiskClass riskClass,
                                  boolean tenantAssignable, boolean freshMfaRequired,
                                  boolean auditRequired, String separationOfDutiesGroup,
                                  String description) {
        return new Metadata(code, module, riskClass, tenantAssignable, freshMfaRequired,
                auditRequired, separationOfDutiesGroup, description);
    }

    private static Map<String, Metadata> build() {
        Map<String, Metadata> entries = new LinkedHashMap<>();
        List.of(
        entry("WMS_IDENTITY_MEMBERSHIP_INVITE", "IDENTITY", RiskClass.G1, false, true, true, null, "Allows an active tenant membership with this permission to invite a user into the same tenant."),
        entry("WMS_INBOUND_RECEIPT_CREATE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Create tenant inbound receipt drafts."),
        entry("WMS_INBOUND_RECEIPT_POST", "WAREHOUSE", RiskClass.C1, false, true, true, "INBOUND_RECEIPT", "Receive and post inbound stock atomically."),
        entry("WMS_INBOUND_RECEIPT_READ", "WAREHOUSE", RiskClass.R1, false, false, false, null, "Read tenant inbound receipts."),
        entry("WMS_INBOUND_RECEIPT_UPDATE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Add lines to editable inbound receipt drafts."),
        entry("WMS_INVENTORY_ADJUSTMENT_APPROVE", "INVENTORY", RiskClass.A1, false, true, true, "INVENTORY_ADJUSTMENT", "Approve inventory adjustments created by another membership."),
        entry("WMS_INVENTORY_ADJUSTMENT_CREATE", "INVENTORY", RiskClass.O1, false, false, true, null, "Generate inventory adjustments from approved stock counts."),
        entry("WMS_INVENTORY_ADJUSTMENT_POST", "INVENTORY", RiskClass.A1, false, true, true, "INVENTORY_ADJUSTMENT", "Post approved inventory adjustments through the inventory posting service."),
        entry("WMS_INVENTORY_ADJUSTMENT_READ", "INVENTORY", RiskClass.R1, false, false, false, null, "Read tenant-scoped inventory adjustments and allocations."),
        entry("WMS_INVENTORY_ADJUSTMENT_REJECT", "INVENTORY", RiskClass.A1, false, true, true, "INVENTORY_ADJUSTMENT", "Reject submitted inventory adjustments with a reason."),
        entry("WMS_INVENTORY_ADJUSTMENT_SUBMIT", "INVENTORY", RiskClass.O1, false, false, true, null, "Submit inventory adjustments for independent approval."),
        entry("WMS_INVENTORY_LEDGER_READ", "INVENTORY", RiskClass.R1, true, false, false, null, "Read tenant immutable inventory ledger entries."),
        entry("WMS_INVENTORY_POST", "INVENTORY", RiskClass.C1, false, true, true, null, "Post validated inventory movements with atomic balance and ledger updates."),
        entry("WMS_INVENTORY_READ", "INVENTORY", RiskClass.R1, true, false, false, null, "Read tenant inventory balances."),
        entry("WMS_INVENTORY_RESERVE", "INVENTORY", RiskClass.C1, false, true, true, null, "Reserve available inventory for controlled outbound workflows."),
        entry("WMS_ITEM_CATEGORY_MANAGE", "INVENTORY", RiskClass.M1, true, false, true, null, "Manage tenant item categories."),
        entry("WMS_ITEM_CATEGORY_READ", "INVENTORY", RiskClass.R1, true, false, false, null, "Read tenant item categories."),
        entry("WMS_ITEM_CREATE", "INVENTORY", RiskClass.M1, true, false, true, null, "Create tenant items."),
        entry("WMS_ITEM_IDENTIFIER_MANAGE", "INVENTORY", RiskClass.M1, false, false, true, null, "Manage tenant item identifiers."),
        entry("WMS_ITEM_READ", "INVENTORY", RiskClass.R1, true, false, false, null, "Read tenant item master data."),
        entry("WMS_ITEM_STATUS_MANAGE", "INVENTORY", RiskClass.M1, false, true, true, null, "Change tenant item lifecycle status."),
        entry("WMS_ITEM_UOM_CONVERSION_MANAGE", "INVENTORY", RiskClass.M1, false, false, true, null, "Manage item-specific UOM conversions."),
        entry("WMS_ITEM_UPDATE", "INVENTORY", RiskClass.M1, true, false, true, null, "Update tenant item metadata."),
        entry("WMS_LOCATION_CREATE", "WAREHOUSE", RiskClass.M1, true, false, true, null, "Create warehouse locations."),
        entry("WMS_LOCATION_READ", "WAREHOUSE", RiskClass.R1, true, false, false, null, "Read warehouse locations."),
        entry("WMS_LOCATION_STATUS_MANAGE", "WAREHOUSE", RiskClass.M1, false, true, true, null, "Change warehouse location status."),
        entry("WMS_LOCATION_UPDATE", "WAREHOUSE", RiskClass.M1, true, false, true, null, "Update warehouse location metadata."),
        entry("WMS_LOT_MANAGE", "INVENTORY", RiskClass.M1, false, false, true, null, "Manage tenant inventory lots."),
        entry("WMS_LOT_READ", "INVENTORY", RiskClass.R1, false, false, false, null, "Read tenant inventory lots."),
        entry("WMS_MEMBERSHIP_ROLE_MANAGE", "AUTHORIZATION", RiskClass.G1, false, true, true, null, "Assign or remove tenant roles from memberships."),
        entry("WMS_MEMBERSHIP_ROLE_READ", "AUTHORIZATION", RiskClass.R1, true, false, false, null, "Read membership role assignments."),
        entry("WMS_OPERATIONAL_REPORT_READ", "REPORTING", RiskClass.R1, false, false, false, null, "Read tenant-scoped WMS operational inventory and workflow summaries."),
        entry("WMS_OUTBOUND_SHIPMENT_ALLOCATE", "WAREHOUSE", RiskClass.C1, false, true, true, null, "Allocate and reserve stock for outbound shipment lines."),
        entry("WMS_OUTBOUND_SHIPMENT_CREATE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Create tenant outbound shipment drafts."),
        entry("WMS_OUTBOUND_SHIPMENT_READ", "WAREHOUSE", RiskClass.R1, false, false, false, null, "Read tenant outbound shipments."),
        entry("WMS_OUTBOUND_SHIPMENT_RELEASE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Release fully allocated outbound shipments for picking."),
        entry("WMS_OUTBOUND_SHIPMENT_UPDATE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Add lines to editable outbound shipment drafts."),
        entry("WMS_PACKING_ASSIGN", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Assign packing sessions to active tenant memberships."),
        entry("WMS_PACKING_COMPLETE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Complete packing after shipment quantities reconcile."),
        entry("WMS_PACKING_CREATE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Create packing sessions from picked outbound shipments."),
        entry("WMS_PACKING_PACKAGE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Create packages and record packed quantities and serials."),
        entry("WMS_PACKING_READ", "WAREHOUSE", RiskClass.R1, false, false, false, null, "Read packing sessions and packages."),
        entry("WMS_PACKING_READY", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Validate and ready packing sessions."),
        entry("WMS_PACKING_SEAL", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Seal completed packages."),
        entry("WMS_PACKING_START", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Start assigned packing sessions."),
        entry("WMS_PERMISSION_CATALOG_READ", "AUTHORIZATION", RiskClass.R1, true, false, false, null, "Read the server-defined permission catalogue."),
        entry("WMS_PICKING_ASSIGN", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Assign picking tasks to active tenant memberships."),
        entry("WMS_PICKING_CONFIRM", "WAREHOUSE", RiskClass.C1, false, true, true, null, "Confirm picked quantities and move reserved stock to staging."),
        entry("WMS_PICKING_CREATE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Create picking tasks from released outbound shipments."),
        entry("WMS_PICKING_READ", "WAREHOUSE", RiskClass.R1, false, false, false, null, "Read tenant picking tasks."),
        entry("WMS_PICKING_START", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Start assigned picking tasks."),
        entry("WMS_PUTAWAY_ASSIGN", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Assign putaway tasks to active tenant members."),
        entry("WMS_PUTAWAY_CONFIRM", "WAREHOUSE", RiskClass.C1, false, true, true, null, "Confirm putaway quantities and move stock."),
        entry("WMS_PUTAWAY_CREATE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Create putaway tasks from posted inbound receipts."),
        entry("WMS_PUTAWAY_READ", "WAREHOUSE", RiskClass.R1, false, false, false, null, "Read tenant putaway tasks."),
        entry("WMS_PUTAWAY_START", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Start assigned putaway tasks."),
        entry("WMS_REPLENISHMENT_CONFIRMATION_REVERSE", "INVENTORY", RiskClass.A1, false, true, true, "REPLENISHMENT_REVERSAL", "Reverse a replenishment confirmation exactly once."),
        entry("WMS_REPLENISHMENT_READ", "INVENTORY", RiskClass.R1, false, false, false, null, "Read tenant-scoped replenishment rules and tasks."),
        entry("WMS_REPLENISHMENT_RULE_MANAGE", "INVENTORY", RiskClass.M1, false, false, true, null, "Create, update, deactivate and plan replenishment rules."),
        entry("WMS_REPLENISHMENT_TASK_ASSIGN", "INVENTORY", RiskClass.O1, false, false, true, null, "Assign a released replenishment task to an active tenant member."),
        entry("WMS_REPLENISHMENT_TASK_CANCEL", "INVENTORY", RiskClass.O1, false, false, true, null, "Cancel an unfinished replenishment task."),
        entry("WMS_REPLENISHMENT_TASK_CONFIRM", "INVENTORY", RiskClass.C1, false, true, true, null, "Confirm same-warehouse replenishment movements."),
        entry("WMS_REPLENISHMENT_TASK_CREATE", "INVENTORY", RiskClass.O1, false, false, true, null, "Create replenishment tasks from eligible rules."),
        entry("WMS_REPLENISHMENT_TASK_RELEASE", "INVENTORY", RiskClass.O1, false, false, true, null, "Release a ready replenishment task."),
        entry("WMS_REPLENISHMENT_TASK_START", "INVENTORY", RiskClass.O1, false, false, true, null, "Start an assigned replenishment task."),
        entry("WMS_ROLE_CREATE", "AUTHORIZATION", RiskClass.G1, true, true, true, null, "Create tenant custom roles."),
        entry("WMS_ROLE_DEACTIVATE", "AUTHORIZATION", RiskClass.G1, true, true, true, null, "Deactivate tenant custom roles."),
        entry("WMS_ROLE_PERMISSION_MANAGE", "AUTHORIZATION", RiskClass.G1, false, true, true, null, "Replace permissions assigned to a tenant custom role."),
        entry("WMS_ROLE_READ", "AUTHORIZATION", RiskClass.R1, true, false, false, null, "Read tenant-scoped roles."),
        entry("WMS_ROLE_UPDATE", "AUTHORIZATION", RiskClass.G1, true, true, true, null, "Update tenant custom role metadata."),
        entry("WMS_SERIAL_MANAGE", "INVENTORY", RiskClass.M1, false, false, true, null, "Register and manage tenant inventory serials."),
        entry("WMS_SERIAL_READ", "INVENTORY", RiskClass.R1, false, false, false, null, "Read tenant inventory serials."),
        entry("WMS_SHIPPING_READ", "WAREHOUSE", RiskClass.R1, false, false, false, null, "Read packed and shipped outbound shipments."),
        entry("WMS_SHIPPING_SHIP", "WAREHOUSE", RiskClass.C1, false, true, true, "OUTBOUND_SHIPMENT", "Post final inventory issue and ship a fully packed outbound shipment."),
        entry("WMS_STOCK_COUNT_APPROVE", "INVENTORY", RiskClass.A1, false, true, true, "STOCK_COUNT", "Approve reviewed stock count variances."),
        entry("WMS_STOCK_COUNT_CANCEL", "INVENTORY", RiskClass.O1, false, false, true, null, "Cancel an eligible pre-submission stock count."),
        entry("WMS_STOCK_COUNT_CREATE", "INVENTORY", RiskClass.O1, false, false, true, null, "Create stock counts and define warehouse location scopes."),
        entry("WMS_STOCK_COUNT_PLAN", "INVENTORY", RiskClass.O1, false, false, true, null, "Validate and plan stock count location scopes."),
        entry("WMS_STOCK_COUNT_READ", "INVENTORY", RiskClass.R1, false, false, false, null, "Read tenant-scoped stock counts and count snapshots."),
        entry("WMS_STOCK_COUNT_RECORD", "INVENTORY", RiskClass.O1, false, false, true, null, "Record physical stock count observations."),
        entry("WMS_STOCK_COUNT_REVIEW", "INVENTORY", RiskClass.A1, false, true, true, "STOCK_COUNT", "Review stock count results and variance evidence."),
        entry("WMS_STOCK_COUNT_START", "INVENTORY", RiskClass.O1, false, false, true, null, "Start a frozen stock count and capture the on-hand snapshot."),
        entry("WMS_STOCK_COUNT_SUBMIT", "INVENTORY", RiskClass.O1, false, false, true, null, "Submit completed stock count results for review."),
        entry("WMS_TENANT_PROFILE_UPDATE", "TENANCY", RiskClass.G1, false, true, true, null, "Allows an active tenant membership to update its own tenant organization profile."),
        entry("WMS_TRANSFER_CREATE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Create transfers and define item/location/lot allocations."),
        entry("WMS_TRANSFER_DISPATCH", "WAREHOUSE", RiskClass.C1, false, true, true, "INTER_WAREHOUSE_TRANSFER", "Dispatch stock from the source warehouse into transit."),
        entry("WMS_TRANSFER_READ", "WAREHOUSE", RiskClass.R1, false, false, false, null, "Read tenant-scoped inter-warehouse transfers."),
        entry("WMS_TRANSFER_RECEIVE", "WAREHOUSE", RiskClass.C1, false, true, true, "INTER_WAREHOUSE_TRANSFER", "Receive, classify damaged goods and reconcile shortages at destination."),
        entry("WMS_TRANSFER_RELEASE", "WAREHOUSE", RiskClass.O1, false, false, true, null, "Validate and release a planned inter-warehouse transfer."),
        entry("WMS_UOM_CATALOG_READ", "INVENTORY", RiskClass.R1, true, false, false, null, "Read the global unit-of-measure catalogue."),
        entry("WMS_WAREHOUSE_CREATE", "WAREHOUSE", RiskClass.M1, true, false, true, null, "Create tenant warehouses."),
        entry("WMS_WAREHOUSE_READ", "WAREHOUSE", RiskClass.R1, true, false, false, null, "Read tenant warehouses."),
        entry("WMS_WAREHOUSE_STATUS_MANAGE", "WAREHOUSE", RiskClass.M1, false, true, true, null, "Change tenant warehouse lifecycle status."),
        entry("WMS_WAREHOUSE_UPDATE", "WAREHOUSE", RiskClass.M1, true, false, true, null, "Update tenant warehouse metadata.")
        ).forEach(metadata -> {
            if (entries.putIfAbsent(metadata.code(), metadata) != null) {
                throw new IllegalStateException("Duplicate permission risk metadata: " + metadata.code());
            }
        });
        return Map.copyOf(entries);
    }

    public static Optional<Metadata> find(String permissionCode) {
        return Optional.ofNullable(ENTRIES.get(permissionCode));
    }

    public static Metadata require(String permissionCode) {
        Metadata metadata = ENTRIES.get(permissionCode);
        if (metadata == null) {
            throw new IllegalArgumentException("Unclassified tenant WMS permission: " + permissionCode);
        }
        return metadata;
    }

    public static Collection<Metadata> all() { return ENTRIES.values(); }
    public static Set<String> permissionCodes() { return ENTRIES.keySet(); }
    public static Set<String> tenantAssignablePermissionCodes() {
        return ENTRIES.values().stream().filter(Metadata::tenantAssignable)
                .map(Metadata::code).collect(Collectors.toUnmodifiableSet());
    }
}
