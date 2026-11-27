# WMS v1 Tenant, Permission, and Command Boundary Audit

> **Historical API boundary audit:** This document records source and test evidence at its stated baseline commit. Use the current [WMS API v1 Reference](../api/wms-api-v1-reference.md) and [Tenant Isolation and Authorization](tenant-isolation-and-authorization.md) for current navigation and policy.


**Baseline:** `7db6742a6e04794eb5b31d54cc58aea45713d026`
**Scope:** WMS v1 HTTP controllers, persisted membership access resolution and representative workflow service boundaries.
**Evidence level:** source inspection. This document does not claim exhaustive runtime proof for every endpoint.

## 1. Tenant identity is not accepted from request data

WMS services receive an authenticated `WmsAuthenticatedPrincipal`. The shared access boundary resolves the active membership using the authenticated user public ID and selected membership public ID. The resulting persisted tenant ID is the source of tenant scope.

Client-supplied public identifiers such as `warehousePublicId`, `itemPublicId`, `locationPublicId`, `membershipPublicId` and workflow document IDs select resources inside that already-established tenant context. They do not establish or override the tenant identity.

## 2. Persisted membership and permission resolution

`WmsMembershipAccessService.resolve(userPublicId, membershipPublicId)` calls `WmsMembershipAccessRepository.findActiveAccess`. The repository query joins:

- `tenant_memberships` to `users` and `tenants`;
- `membership_roles` to tenant-matched `roles`;
- `role_permissions` to `permissions`.

The query requires the exact membership public ID and authenticated user public ID, with active membership, user and tenant status; role joins are tenant-matched and active. If no active row is found, access is denied. Effective permission codes are collected from persisted rows.

Representative WMS service access checks compare the resolved tenant, membership and user identifiers with the authenticated principal and require the relevant persisted permission before database work.

## 3. Tenant-scoped resource selection

Source inspection confirmed tenant predicates on representative workflow resource lookups:

| Area | Representative boundary |
|---|---|
| Warehouse | Warehouse and location reads/updates use the resolved tenant ID; warehouse public IDs are not globally trusted selectors. |
| Outbound shipment | Shipment headers and child lines/allocations are selected by tenant ID; joined warehouse, item and partner rows are tenant-matched. |
| Replenishment | Manual task creation selects warehouse, item, source location and destination location with the resolved tenant ID; locations are also constrained to the selected warehouse. |
| Stock count | Count and location scope operations use the resolved tenant ID; resource rows are locked/selected with tenant predicates. |
| Inter-warehouse transfer | Transfer headers, lines, allocations, locations, lots and serials are read with tenant-scoped joins and predicates. |
| Role management | Role and membership lookups are constrained to the resolved tenant; missing membership/role IDs cannot select another tenant's row. |

This supports a source-level conclusion that these inspected service paths do not treat a client-supplied public ID as authorization.

## 4. Command boundary invariants

- Controller request bodies are validated with `@Valid` in the inspected WMS controller inventory.
- Service methods resolve active membership and permissions before performing commands.
- Workflow state/version checks remain in application services, not in client-side UI.
- Inventory mutation remains delegated to the inventory posting/reservation services; this audit does not introduce direct balance or ledger writes.
- Tenant-scoped row selection and locking remain inside service transactions.
- Idempotency keys and one-time reversal constraints remain command-specific; no generic idempotency behavior is inferred.

## 5. Evidence classification

| Claim | Classification | Limit |
|---|---|---|
| Shared access service resolves active persisted membership and permissions | **VERIFIED — source inspection** | Database behavior is covered by existing integration suites; this document is not a fresh CI result. |
| Authenticated principal is checked against resolved tenant/membership/user context in representative services | **VERIFIED — source inspection** | Must still be maintained in every newly added service. |
| Warehouse/item/location public IDs are tenant-scoped in inspected workflow paths | **VERIFIED — source inspection** | Not a formal proof of every SQL statement in every module. |
| All WMS routes have equivalent tenant isolation under HTTP execution | **PARTIALLY VERIFIED** | WP9/WP10 need cross-tenant HTTP probes for representative route families. |
| No cross-tenant leak exists anywhere in the full application | **UNVERIFIED** | Requires final structural/security audit and integrated regression. |

## 6. Follow-up tests

WP9/WP10 should include focused negative cases where a principal from Tenant A supplies a valid public ID belonging to Tenant B for:
- warehouse/location selection;
- item and lot selection;
- outbound shipment and transfer document lookup;
- replenishment and stock-count resources;
- role and membership management.

Expected behavior is denial or not-found according to the endpoint's disclosure policy, with no cross-tenant data mutation or leakage. Do not use a client-provided tenant ID as the expected test fixture.

## 7. Decision

Keep tenant resolution and permission enforcement in the backend application boundary. Do not add tenant IDs to request DTOs, and do not rely on frontend filtering or hidden controls for authorization.
