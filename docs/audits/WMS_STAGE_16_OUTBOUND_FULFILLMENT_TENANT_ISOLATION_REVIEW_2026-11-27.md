# Stage 16 — Outbound Fulfillment Tenant-Isolation Source Review

Date: 2026-11-27  
Scope: source-level review of shipping, picking, and packing application services on the reviewed mainline commit. This is not a penetration test or runtime certification.

## Files reviewed

- `src/main/java/com/zirocraft/zirologistics/wms/shipping/application/WmsShippingManagementService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/picking/application/WmsPickingManagementService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/packing/application/WmsPackingManagementService.java`

## Review approach

Reviewed tenant principal usage, permission gates, tenant activation locks, tenant predicates in reads and writes, ownership equality in joins, nested identifier resolution, and workflow state transitions. The review focused on whether client-supplied public identifiers can cause one tenant's fulfillment data to be read or mutated through another tenant's context.

## Findings

### 1. Shipping

- Read paths for shipment lists and detail constrain shipment/package/session data by tenant ID.
- Warehouse and carrier joins include tenant equality; package/session joins include tenant equality.
- Ship mutation obtains a tenant lock and requires the dedicated `WMS_SHIPPING_SHIP` permission.
- Shipment, line, allocation, serial, picking, packing, item, lot, location, membership, and transfer-related lookups inspected use tenant-scoped predicates or tenant-equality joins.
- Final shipment state transition includes both record ID and tenant ID, with expected prior status.
- Inventory posting is delegated to the inventory posting service with the authenticated tenant principal; this is an important second enforcement boundary.

### 2. Picking

- List/detail queries constrain tasks and nested lines by tenant ID; joins to shipments, warehouses, memberships, items, locations, allocations, lots, and serials include tenant equality.
- Create, assign, start, and confirm operations lock the active tenant and use distinct permissions.
- Shipment, allocation, membership, task, task-line, serial, and staging-location resolution inspected is tenant-scoped.
- Mutating statements for task, shipment, allocation, line, and serial relationships include tenant predicates.
- Inventory reservation consumption and stock transfer are delegated with the authenticated principal; downstream posting remains responsible for its own tenant and permission checks.

### 3. Packing

- List/detail queries constrain sessions, packages, package lines, serial links, shipment, picking lines, items, and memberships by tenant ID.
- Create, ready, assign, start, package, add-line, seal, and complete operations lock the active tenant and use distinct permissions.
- Nested package/session/line/serial lookups inspected include tenant predicates and tenant-equality joins.
- Package, session, and shipment state changes inspected include tenant ID and expected status.
- Completion checks compare packed quantities and serial allocations within the same tenant before marking the shipment packed.

## Conclusion

No concrete cross-tenant read or write vulnerability was confirmed in the reviewed service code. The three services consistently derive access from the authenticated tenant principal, validate live membership/permissions, and scope the reviewed SQL operations to the tenant.

This is a source-review conclusion only. It does not establish that all HTTP routes, all service entry points, all SQL paths, or all concurrency behaviors are secure.

## Required follow-up verification

1. Add or confirm MySQL integration tests for foreign-tenant shipment/task/session/package/line/serial public IDs, including nested and bulk identifiers.
2. Verify not-found versus forbidden semantics do not disclose foreign-tenant record existence.
3. Exercise concurrent picking confirmations, package sealing/completion, and shipment shipping transitions; verify optimistic/state predicates and rollback on partial failure.
4. Verify inventory posting/reservation delegation rejects mismatched principal, tenant, warehouse, and request identifiers.
5. Verify audit event insertion failure rolls back the associated workflow mutation.
6. Add HTTP boundary tests proving tenant A cannot read or mutate tenant B's fulfillment resources through every controller route.

## Limitations

The review did not execute the application, run SQL against MySQL, inspect every controller/route, or prove the absence of vulnerabilities. It does not cover support-grant access, background workers, external integrations, or authorization outside the three named services.
