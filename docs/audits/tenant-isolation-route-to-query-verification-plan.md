# Tenant Isolation Route-to-Query Verification Plan

**Status:** Active verification plan; tenant isolation remains partially verified.
**Baseline commit:** `2b72fb4b7870e8312b643a2b5fb71321eab0ab45`.
**Related baseline:** `docs/security/tenant-isolation-and-authorization.md`.

## 1. Existing positive evidence

- `WmsMembershipAccessService` resolves the authenticated user and selected membership together through persisted access rows.
- The access repository constrains user, membership, tenant, active membership state, tenant role ownership, and permission joins.
- Tenant-scoped services are expected to compare resolved principal identity and use the resolved internal tenant ID in SQL.
- Existing MySQL/Testcontainers and focused boundary tests cover selected identity, warehouse, inbound, outbound, inventory, putaway, picking, packing, shipping, transfer, stock count, adjustment, replenishment, reporting, and role-management paths.
- The existing security audit explicitly remains **PARTIALLY VERIFIED**, not a universal certification.

## 2. Verification method

Build a route-to-query traceability matrix. For every controller mapping, record:
1. HTTP method and route;
2. request identifiers and collection filters;
3. authenticated principal and membership resolution;
4. operation-specific permission;
5. application service entry point;
6. every SQL statement/repository method reached;
7. tenant-owned tables and parent/child relationships touched;
8. negative cross-tenant test evidence.

Trace detail, list, search, count, export, create, update, delete, confirm, post, release, and cancel operations independently. A secure read path does not prove the corresponding mutation path is secure.

## 3. High-risk cases to test

- Parent ID from tenant A combined with child ID from tenant B.
- Bulk request containing mixed-tenant public IDs.
- Nested references such as warehouse/location, item/lot/serial, inventory, shipment/order, transfer, adjustment, and replenishment task.
- Search, pagination, count, and report filters where a tenant predicate can be omitted.
- State transitions and posting operations after a resource was loaded.
- Duplicate/idempotency keys reused across tenants.
- Inactive tenant, membership, role, or user state changes between authentication and operation.
- Background workers and command runners that do not receive an HTTP tenant principal.
- Platform lifecycle commands and audit actor attribution.

## 4. Security boundary findings to preserve

- A selected membership public ID is a selector, not a credential.
- Tenant ID must originate from persisted membership resolution, never from an arbitrary request parameter or body.
- Cross-tenant identifiers should fail with non-disclosing not-found behavior where appropriate.
- A platform operator audit label is not an authenticated platform identity.
- Platform control-plane authority must not imply access to warehouse transaction data.

## 5. Evidence classification

Every result must be labelled as one of:
- **MySQL integration verified** — the actual MySQL query and schema were exercised.
- **HTTP boundary verified** — the route, authentication, and principal forwarding were exercised.
- **Service boundary verified** — selected service behavior or SQL predicates were checked, but not necessarily against MySQL.
- **Source reviewed** — code path inspected without runtime proof.
- **Not covered** — no adequate evidence yet.

Do not infer broad coverage from test class names or the total number of tests.

## 6. Exit criteria

1. Every WMS controller mapping is represented in the matrix.
2. Every tenant-owned query path has an explicit tenant/parent scope review.
3. High-risk nested and bulk references have negative MySQL integration tests.
4. Async, scheduled, and command execution identities are explicitly documented and tested.
5. Findings are tracked with severity, source evidence, test evidence, and remediation PR.
6. The final assurance statement remains conservative until all in-scope paths meet their evidence gate.
