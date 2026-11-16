# WMS Warehouse Master Data — Architecture & API Contract

**Status:** Proposed implementation contract for roadmap Stage 7.  
**Runtime:** WMS-only modular monolith.  
**Schema:** Frozen WMS V1; any missing persisted capability requires a reviewed additive migration.  
**Verification:** Disposable MySQL 8.4 Testcontainers only.  
**Scope:** Tenant-scoped warehouse and warehouse-location master data. Item catalogue, UOM, inventory, inbound/outbound and warehouse execution workflows are separate roadmap stages.

## 1. Objective and boundaries

Implement a safe, tenant-scoped master-data boundary for warehouses and hierarchical warehouse locations (zone, rack, shelf, bin, receiving, storage, picking, packing, shipping, staging, quarantine, returns, damage and other location types).

Do not generate entities for every table. Do not reuse legacy single-company WarehouseEntity or flat BinEntity as the WMS V1 model. Use the frozen WMS schema and explicit WMS runtime package.

A warehouse belongs to exactly one tenant. A location belongs to exactly one warehouse and tenant. A client-supplied tenant ID is never an authorization input.

## 2. Frozen schema authority

### warehouses

Existing V1 columns:
- id, public_id, tenant_id;
- warehouse_code (64), warehouse_name (150), description (500);
- address/contact fields, country_code, IANA timezone;
- status, created_at, updated_at, optimistic version.

Existing constraints:
- unique public ID;
- unique (tenant_id, warehouse_code);
- unique (id, tenant_id) for tenant-aware child references;
- FK tenant ownership;
- status CHECK: ACTIVE, INACTIVE, MAINTENANCE, CLOSED.

### warehouse_locations

Existing V1 columns:
- id, public_id, tenant_id, warehouse_id, nullable parent_location_id;
- location_code (100), location_name (150), location_type, description;
- optional max weight/volume capacities and corresponding UOM codes;
- is_pickable, is_receivable, allow_mixed_skus, allow_mixed_lots;
- picking/putaway sequences, status, timestamps, optimistic version.

Existing constraints:
- unique public ID;
- unique (warehouse_id, location_code);
- tenant-aware unique (id, warehouse_id, tenant_id);
- composite FK (warehouse_id, tenant_id) to warehouses;
- composite self-FK (parent_location_id, warehouse_id, tenant_id) to the same warehouse;
- CHECK constraints for location type/status, non-negative sequences/capacities and capacity/UOM pairing.

The database already enforces parent and child tenant/warehouse consistency. Application logic must additionally prevent hierarchy cycles and unsafe lifecycle changes.

## 3. Authorization contract

Use current authenticated WMS principal and re-resolve persisted membership/effective permissions through WmsMembershipAccessService on each operation. Compare tenant, membership and user public identifiers against the authenticated principal exactly as existing tenant and role services do.

Proposed server-defined permissions:
- WMS_WAREHOUSE_READ
- WMS_WAREHOUSE_CREATE
- WMS_WAREHOUSE_UPDATE
- WMS_WAREHOUSE_STATUS_MANAGE
- WMS_LOCATION_READ
- WMS_LOCATION_CREATE
- WMS_LOCATION_UPDATE
- WMS_LOCATION_STATUS_MANAGE

These permissions are global catalogue entries, not client-created strings. Existing and newly provisioned TENANT_OWNER roles receive an explicit allowlist through an additive migration and the provisioning service. Only the permissions explicitly designated delegable may be granted to custom roles; no wildcard permissions.

Every query and mutation includes the resolved tenant ID. Warehouse and location public IDs are opaque references; cross-tenant IDs return safe not-found behavior and must never disclose existence.

## 4. API contract

Base path: /api/wms/v1/tenants/current.

### Warehouses

- GET /warehouses — bounded list for current tenant.
- POST /warehouses — create warehouse.
- GET /warehouses/{warehousePublicId} — read one warehouse.
- PUT /warehouses/{warehousePublicId} — update editable metadata with expectedVersion.
- POST /warehouses/{warehousePublicId}/status — explicit status transition with expectedVersion.

Editable fields: code, name, description, address, country, contact details and timezone. Clients cannot set tenant ID, public ID, internal ID, version, or timestamps.

Warehouse code is trimmed and normalized to uppercase using Locale.ROOT; names are trimmed and bounded. Timezone must be a valid IANA ZoneId. Country code must be a valid ISO-3166-1 alpha-2 code. Email and phone are bounded and validated conservatively.

Status transition policy:
- ACTIVE → INACTIVE, MAINTENANCE, CLOSED
- INACTIVE → ACTIVE, CLOSED
- MAINTENANCE → ACTIVE, INACTIVE, CLOSED
- CLOSED is terminal

A warehouse is never physically deleted through this API. Closing a warehouse with non-zero stock or active operational documents must be rejected once those dependent workflows are implemented; Stage 7 must not pretend to enforce checks against workflows that do not yet exist. Until then, status transitions remain explicit and audited.

### Locations

- GET /warehouses/{warehousePublicId}/locations — bounded list for one warehouse; optional parent/type/status filters.
- POST /warehouses/{warehousePublicId}/locations — create location.
- GET /warehouses/{warehousePublicId}/locations/{locationPublicId} — read one location.
- PUT /warehouses/{warehousePublicId}/locations/{locationPublicId} — update editable metadata with expectedVersion.
- POST /warehouses/{warehousePublicId}/locations/{locationPublicId}/status — explicit status transition with expectedVersion.

The warehouse path and location reference must both resolve inside the current tenant. Clients cannot set tenant/warehouse/internal IDs, public ID, version, or timestamps.

Location code is trimmed and normalized to uppercase. location_type and status must be members of the exact schema CHECK allowlists. Parent, if present, must be a location in the same tenant and warehouse. Reject self-parenting and any update that would create a cycle. Parent hierarchy depth is not artificially capped unless a separately approved product limit is introduced.

Capacity values must be non-negative and supplied together with their corresponding UOM code. A UOM code must be bounded and normalized; Stage 7 validates the paired field shape but does not yet implement UOM conversion or dimensional compatibility (Stage 8/catalogue work). Picking/putaway sequences must be non-negative. Boolean flags are explicit.

Location status values are ACTIVE, INACTIVE, BLOCKED, MAINTENANCE. A location status transition is explicit and audited. If a location contains non-zero inventory, transitions that would make it unavailable for putaway/picking must be rejected; this check must be implemented using tenant/warehouse-scoped inventory_balances queries and covered by MySQL integration tests. Never silently relocate or adjust inventory as a side effect of master-data changes.

## 5. Persistence, concurrency and audit

Use explicit JDBC or narrowly scoped WMS persistence adapters with SQL predicates containing tenant and parent aggregate IDs. Do not expose internal numeric IDs in API responses.

- Create/update/status mutations are transactional.
- Lock the warehouse row first for location mutations; use a consistent lock order.
- Use optimistic version checks for metadata/status updates. A stale version returns HTTP 409 and performs no audit write.
- Use database unique constraints as final protection for duplicate codes; map duplicate-key errors to safe conflict responses.
- Audit each successful create/update/status transition in tenant_audit_events within the same transaction.
- Audit events are append-only from application behavior. Payloads contain bounded before/after business metadata, never credentials or secrets.
- If audit insertion fails, the warehouse/location mutation rolls back.
- Use a unique operation reference for each audit mutation where required by the existing unique audit constraint; retries must not create duplicate successful events.

Proposed audit event types:
- WAREHOUSE_CREATED, WAREHOUSE_UPDATED, WAREHOUSE_STATUS_CHANGED
- WAREHOUSE_LOCATION_CREATED, WAREHOUSE_LOCATION_UPDATED, WAREHOUSE_LOCATION_STATUS_CHANGED

V1–V9 remain unchanged. Add V10 only to expand the audit event CHECK and seed the new permission catalogue/owner grants.

## 6. Verification gates

Use disposable MySQL 8.4 Testcontainers. Do not connect to, inspect, or mutate any user/development/production database.

Required tests:
1. V10 migrates from both a fresh V1 path and an existing V9 path; V1–V9 checksums remain unchanged.
2. Existing and repeatably provisioned tenant owners receive the exact eight warehouse/location permissions.
3. Missing permissions deny reads and writes.
4. Tenant A cannot enumerate/read/update tenant B warehouses or locations by guessing public IDs.
5. A location cannot reference a warehouse or parent in another tenant/warehouse.
6. Duplicate warehouse/location codes are rejected without partial writes.
7. Invalid enum, timezone, country code, capacity/UOM pairing and negative sequence/capacity are rejected.
8. Parent self-reference and cycles are rejected.
9. Stale versions return conflict with no mutation or audit event.
10. Audit failure rolls back the business mutation.
11. Concurrent code creation and concurrent updates preserve uniqueness/version invariants.
12. Location status changes are blocked when the location has non-zero stock where required by policy.
13. API tests cover authentication, permission gates, validation and safe cross-tenant errors.

## 7. Explicit non-goals

- Item/category/brand/catalogue and UOM management.
- Inventory balances, ledger posting, reservations, adjustments or stock movement.
- Inbound receiving, putaway, picking, packing, shipping, replenishment and cycle counts.
- Warehouse-to-membership assignment: frozen V1 has no such relationship. Do not claim per-user warehouse authorization; Stage 7 enforces tenant scope only.
- Physical deletion of warehouses or locations.
- Public tenant selection or client-supplied tenant IDs.
- Production database migrations or manual data changes.

## 8. Delivery sequence

1. Review this contract and the frozen DDL.
2. Add V10 permission/audit migration and migration Testcontainers coverage.
3. Implement tenant-scoped warehouse application service and HTTP API.
4. Implement hierarchical location service/API with cycle and occupancy safeguards.
5. Wire explicit owner grants for both existing and newly provisioned tenants.
6. Add service, HTTP/security and MySQL integration tests.
7. Verify CI, merge by PR, then proceed to Stage 8 inventory/catalogue/UOM design.
