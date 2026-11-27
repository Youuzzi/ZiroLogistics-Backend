# WMS Tenant Isolation Source Review — Inventory and Fulfillment Slice

Date: 2026-11-27
Scope: inventory reservation/posting, stock count, replenishment task workflows
Review type: bounded source inspection; no runtime behavior changes

## Files inspected

- `src/main/java/com/zirocraft/zirologistics/wms/inventory/application/WmsInventoryReservationService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/inventory/application/WmsInventoryPostingService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/stockcount/application/WmsStockCountManagementService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/replenishment/application/WmsReplenishmentTaskService.java`

## Evidence observed

### Inventory reservation

- Reservation and consumption derive access from the authenticated tenant principal, lock the active tenant row, resolve the live membership, and compare user, tenant, tenant public ID, membership ID and membership public ID against the principal.
- Reservation requires the persisted `WMS_INVENTORY_RESERVE` permission.
- Warehouse, location, item, lot and serial resolution includes `tenant_id`; location resolution is also bound to the selected warehouse.
- Inventory balance reads and conditional writes include tenant, warehouse, location, item, lot and stock status. Writes are row-locked and versioned.
- Serial allocation checks join outbound allocation records with tenant equality and filter the requested serials by tenant.
- The dynamic table name used by `lockIds` is selected only by fixed internal call sites (warehouse, location, item, lot, serial); it is not derived from request data. Each lookup includes `id` and `tenant_id`.
- Frozen stock-count scopes are checked with tenant equality across the scope/count join.

### Inventory posting

- Reviewed SQL text blocks containing `WHERE` clauses all included an explicit `tenant_id` predicate in the inspected service.
- Workflow posting is restricted to a fixed permission-to-movement allowlist; a caller cannot submit an arbitrary workflow permission/movement pairing.
- Posting is the documented sole application boundary for inventory balance, ledger and serial writes. This source slice does not prove every caller is correctly authorized; workflow entrypoints and end-to-end posting tests remain part of the wider review.

### Stock count

- Stock count headers, scopes, lines, results, serials, adjustment links, warehouse/location/item/lot records and membership joins are tenant-bound in the inspected queries.
- Parent-child joins consistently include tenant equality (for example count/scope, scope/line, line/item, location/warehouse, reviewer/counter membership).
- Scope and line lookups use tenant ID together with the parent stock-count identity; updates include tenant predicates and expected state/version checks.
- Freeze checks and result aggregation remain within the tenant and stock-count scope.

### Replenishment task

- Reviewed SQL text blocks containing `WHERE` clauses all included an explicit `tenant_id` predicate in the inspected service.
- Task headers, lines, warehouse, source/destination locations, items, lots, balances and membership assignments are resolved through tenant-bound predicates or tenant-equality joins.
- Manual task creation aggregates requested quantities by source bucket and locks tenant-scoped stock rows before creating task lines.
- Task release uses tenant ID, expected version, current status and release state in the conditional update.

## Findings

- No concrete cross-tenant data access vulnerability was confirmed in these four inspected services.
- The sampled SQL showed explicit tenant predicates and tenant-equality joins at the inspected persistence boundaries.
- The fixed internal table-name call sites in reservation locking are not user-controlled in the reviewed implementation.
- The posting service's internal permission allowlist is a useful control, but the complete workflow-to-posting call graph and transaction rollback behavior still require separate verification.

## Remaining verification

- Exercise negative integration cases with identifiers belonging to another tenant for every nested/bulk identifier collection, including serial arrays and stock-count/replenishment line collections.
- Verify indistinguishable not-found behavior where the API contract intends to avoid resource enumeration.
- Exercise concurrent reservation/posting, stock-count freeze races, task release/assignment races, idempotency replay and rollback under MySQL 8.4.
- Trace every workflow posting caller and confirm the persisted permission, tenant principal, movement type, idempotency key and transaction boundary are enforced end-to-end.
- Review tenant-scoped background execution and non-HTTP entrypoints separately.

## Evidence limits

This is a source review of the named files only. It is not a full repository certification, penetration test, production configuration review or substitute for runtime MySQL integration tests. Absence of a confirmed finding in this slice is not proof that no vulnerability exists elsewhere.
