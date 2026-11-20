# Stage 16.5 — Inventory Tenant Boundary Source Audit

## Scope and evidence type

This is a source-code review of the inventory posting application boundary on the default branch. It is **not** a runtime penetration test, an HTTP integration test, or proof of tenant isolation under deployed configuration. It complements the warehouse tenant boundary tests in PR #176 and the broader gap audit in PR #174.

Reviewed source:
- `src/main/java/com/zirocraft/zirologistics/wms/inventory/application/WmsInventoryPostingService.java`

## Positive source observations

The posting service resolves an access context before processing and carries the resolved tenant ID into inventory operations. Source inspection found tenant predicates and tenant-aware joins in the following paths:

- Approved manual adjustment lookup: `inventory_adjustments.tenant_id` plus public ID; linked adjustment lines are constrained by tenant and adjustment ID.
- Approved stock-count adjustment: adjustment, link, and stock-count joins match tenant IDs; stock-count public ID is checked.
- Transfer-serial loss reconciliation: serial lookup uses tenant ID and public ID; update repeats tenant ID and expected status.
- Reference resolution: warehouse, warehouse location, item, lot, and serial reads include tenant ownership checks; location references also bind the warehouse.
- Adjustment allocations and stock-count freeze checks: joins include tenant ownership for related adjustment lines, warehouse/location/item/lot/serial references, and stock counts.
- Balance and ledger writes include tenant IDs in inserted rows; balance creation/lookup is tenant-keyed.

## Important caveat

A source review of SQL predicates does not prove all cross-tenant paths are denied in the real request pipeline. In particular, it does not exercise controller binding, authentication/membership resolution, transaction behavior, database constraints, or the effect of a rejected operation on balances and ledger rows.

## Recommended runtime evidence before Stage 16 closure

1. Create tenant A and tenant B fixtures with distinct inventory resources.
2. Authenticate as tenant A and attempt reads/posts using tenant B public IDs for warehouse, location, item, lot, and serial references where the workflow accepts them.
3. Verify each attempt is rejected and no tenant B data is returned.
4. Verify rejected write/post requests create no balance mutation, ledger entry, serial transition, or audit event beyond explicitly intended security logging.
5. Repeat permitted same-tenant operations to show positive controls.
6. Use the application request role and Testcontainers MySQL path, not a privileged shortcut.

## Closure classification

- Source SQL tenant scoping: **observed in reviewed source**.
- Inventory cross-tenant runtime denial: **not verified by this audit**.
- Full Stage 16 tenant-isolation gate: **still pending runtime evidence**.
