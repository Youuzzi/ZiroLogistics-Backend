# WMS Final Database Design Baseline

- **Document ID:** DB-14
- **Version:** 0.1
- **Status:** Frozen implementation baseline — subject to product-owner review before merge or execution
- **Parent:** DB-01 WMS Database Architecture
- **Supersedes:** Open decision recommendations in DB-03 through DB-13 where this document gives an explicit V1 decision
- **Target:** MySQL 8.4 LTS, InnoDB, Flyway, Java 21 / Spring Boot 3.5.x
- **Product stage:** Initial release for small and medium warehouses

## 1. Authority and closure

This document consolidates the decisions needed to produce the first physical SQL schema. It is the single implementation reference for WMS Database V1. Earlier documents remain useful for rationale and test scenarios, but their unresolved decision tables must not reopen a decision that is explicitly frozen here.

The baseline is intentionally practical for one company operating multiple warehouses. It is not a full enterprise WMS or multi-tenant SaaS design.

Decision labels:
- **FROZEN V1:** selected for the first schema and must be implemented consistently.
- **DEFERRED:** intentionally excluded from V1; not a blocker.
- **APPLICATION RULE:** cannot be guaranteed by a simple relational constraint alone and must be enforced and integration-tested by the backend.

A later change requires a new, explicit architecture decision with impact analysis. It must not trigger a repeat of this completed review.

## 2. Frozen V1 decisions

| ID | Area | V1 decision | Status |
|---|---|---|---|
| DEC-01 | Ownership | One explicit Company owns multiple Warehouses and Items. Users belong to that Company. | FROZEN V1 |
| DEC-02 | User access | RBAC is normalized (Role, Permission, UserRole, RolePermission); warehouse access is a separate UserWarehouseAccess relation. | FROZEN V1 |
| DEC-03 | Location | One generic Location table per warehouse, optional parent, controlled LocationType; no mandatory aisle/rack/shelf tables. | FROZEN V1 |
| DEC-04 | Inventory status | Physical/operational condition only: AVAILABLE, DAMAGED, QUARANTINED. Reservation is not a status. | FROZEN V1 |
| DEC-05 | Reservation | Formal reservation allocation is included in V1 and references outbound order line plus inventory balance. | FROZEN V1 |
| DEC-06 | Receiving | Accepted goods post when physically confirmed into an inventory-bearing RECEIVING location. Rejected goods that never enter custody do not become on-hand. | FROZEN V1 |
| DEC-07 | Putaway | Putaway is a confirmed location-to-location movement from RECEIVING/STAGING to destination. | FROZEN V1 |
| DEC-08 | Transfer | V1 supports atomic immediate transfer between locations, including warehouses. No in-transit staging/partial destination receipt in V1. | FROZEN V1 |
| DEC-09 | Outbound | Reservation -> picking execution -> shipment/dispatch. Picking remains on-hand; dispatch is the custody boundary that reduces on-hand. | FROZEN V1 |
| DEC-10 | Stock control | Count sessions record observations; approved variance posts through an adjustment and inventory transaction. No direct balance overwrite. | FROZEN V1 |
| DEC-11 | Ledger | inventory_transactions + inventory_transaction_lines are the single authoritative append-only posting history. inventory_balances is a maintained projection. No second writable inventory_ledger table. | FROZEN V1 |
| DEC-12 | Tracking | Lot, expiry, serial, license plate/handling unit, owner inventory, and quality attributes are deferred. | DEFERRED |
| DEC-13 | UOM | Base-UOM-only transactions in V1. No generic conversion engine or conversion table. | FROZEN V1 |
| DEC-14 | Quantity precision | DECIMAL(18,3) for stored quantities; application policy limits fractional scale by UOM. | FROZEN V1 |
| DEC-15 | Weight precision | DECIMAL(18,6) for KG per one Base UOM. Weight is optional unless a capacity-managed location requires it. | FROZEN V1 |
| DEC-16 | Occupancy/capacity | Optional weight capacity per location, stored in KG with DECIMAL(24,9) precision. Occupancy is a transactionally maintained projection. | FROZEN V1 |
| DEC-17 | Identifiers | Signed BIGINT internal PK/FK; CHAR(36) UUID public_id for externally addressable business records. | FROZEN V1 |
| DEC-18 | Time | DATETIME(6) stored as UTC instants. Warehouse IANA timezone is stored separately for local operational display/business-date conversion. | FROZEN V1 |
| DEC-19 | Deletion | Restrictive foreign keys for operational history; master data is deactivated, not cascade-deleted. Posted records are immutable in normal application flows. | FROZEN V1 |
| DEC-20 | Fresh install | New schema uses a separate Flyway location and a genuinely empty database. Existing zirologistics_db and legacy V1/V2 migrations are not reused or modified. | FROZEN V1 |

## 3. Organization and access model

### 3.1 Ownership

- A Company owns Warehouses and Items.
- A Warehouse belongs to exactly one Company.
- A Location belongs to exactly one Warehouse.
- Inventory ownership is resolved through Item and Location; the backend must reject cross-company combinations.
- User belongs to one Company in V1.
- UserWarehouseAccess explicitly grants warehouse scope. A role does not implicitly grant access to every warehouse.

### 3.2 Authorization

Use normalized tables:
- companies
- users
- roles
- permissions
- user_roles
- role_permissions
- user_warehouse_access

Do not guess or seed business role names from generic WMS examples. The schema stores role and permission definitions; the application bootstrap/setup process creates the first company, initial administrator, and approved role catalogue. Password hashes and secrets are never included in SQL seed data.

Email and SKU uniqueness are company-scoped. Warehouse code is unique within company. Location code is unique within warehouse.

## 4. Warehouse and location model

Use a single locations table with:
- warehouse_id;
- optional parent_location_id;
- location_type_id;
- location_code and display_name;
- active state;
- optional max_weight_capacity_kg;
- current_weight_occupancy_kg projection;
- audit fields and optimistic version.

V1 location types:
- RECEIVING — may hold received stock;
- STAGING — may hold temporary stock;
- STORAGE — normal storage;
- PICKING — picked stock remains on-hand here;
- DISPATCH — stock remains on-hand until dispatch is confirmed;
- QUARANTINE — physical non-allocatable holding.

PACKING is not a separate inventory location type in V1; it can be represented by a workflow step or DISPATCH location. RETURNS and VIRTUAL_IN_TRANSIT are deferred.

A location may hold stock only when its type is inventory-bearing. A parent location is a grouping node and cannot hold stock when it has children. Same-warehouse parentage, no self-parent, and no cycles are enforced in the application and tested. Location code is unique per warehouse.

Capacity is optional. If enabled, it is weight-based only in V1. A capacity-managed destination requires an item weight in KG per Base UOM. All physical statuses count toward occupancy; reservation does not change occupancy. Occupancy changes only when physical stock quantity/location changes.

## 5. Item and UOM model

V1 controlled UOM codes:
- PCS: whole quantities;
- BOX: whole quantities;
- KG: up to 3 fractional digits;
- GR: whole quantities;
- ML: whole quantities.

Transactions store quantity in the item's Base UOM only. The database stores the UOM code and quantity exactly; backend validation enforces the UOM-specific scale and rejects zero, negative, unsupported, or excess-precision movement input. Insignificant trailing zeroes are accepted when the numeric value is exactly representable.

Physical weight semantics:
- weight_per_base_uom_kg is the KG weight of exactly one unit of the item's Base UOM;
- KG Base UOM uses 1.000000 KG/KG;
- GR Base UOM uses 0.001000 KG/GR;
- PCS and BOX require product-specific physical weight when capacity validation is enabled;
- BOX weight is gross packaged weight when used for storage-capacity calculations;
- ML requires product-specific density; never assume 1 ML = 1 GR.

Weight may be NULL for items not stored in capacity-managed locations. Once an item has posted movement history, Base UOM and weight semantics cannot be changed through ordinary master-data updates. A controlled migration/revaluation is a separate change.

V1 does not include item-UOM conversion tables, barcode, category hierarchy, or packaging-level UOM.

## 6. Inventory model and invariants

### 6.1 Balance identity

The V1 balance key is:
(item_id, location_id, inventory_status_id)

A balance row stores on_hand_quantity in Base UOM. A unique constraint enforces one row per identity. Warehouse is resolved through Location and is not redundantly writable on the balance.

### 6.2 Status semantics

- AVAILABLE: physically present and eligible for normal allocation.
- DAMAGED: physically present but not eligible for normal allocation.
- QUARANTINED: physically present and held from normal allocation.

All three contribute to on-hand and physical occupancy. Only AVAILABLE contributes to normal allocatable stock.

### 6.3 Reservation

Reservations are separate allocation rows linked to an outbound order line and an inventory balance. Active reservations reduce available quantity but do not reduce on-hand or occupancy.

Available quantity for an eligible balance is:
on_hand_quantity - active_reserved_quantity.

Reservation creation/release/consumption must lock or atomically condition the relevant balance and reservation rows. A reservation cannot exceed available quantity. Picking consumes or releases the corresponding reservation according to confirmed picked quantity; cancellation releases only the unpicked remainder.

### 6.4 Posting and ledger

inventory_transactions is the posting header and inventory_transaction_lines are the authoritative signed effects. Each line identifies item, location, status, and signed quantity_delta. A transfer posts two lines under one transaction: negative at source and positive at destination. A status change posts a negative line for the old status and positive line for the new status at the same location.

inventory_balances is a maintained current-state projection. Balance changes, transaction header/lines, source execution linkage, occupancy changes, document execution state, and idempotency outcome commit in one database transaction.

Do not create a second independently writable inventory_ledger table. Read-only views may be introduced later if a query projection is needed.

### 6.5 Idempotency and reversal

Each inventory transaction carries a company-scoped idempotency key and payload hash. Same key + same semantic payload returns the original result; same key + different payload is rejected. The unique database constraint is the final concurrency guard.

Posted transactions and lines are immutable through normal application operations. Corrections use compensating transactions referencing the original transaction. The application verifies that cumulative reversal does not exceed the original effect. Do not delete or edit posted history.

### 6.6 Negative stock and concurrency

Negative on-hand and over-reservation are rejected. Posting must use transaction-safe row locking or atomic conditional updates, consistent lock ordering, and bounded safe retry for deadlocks. Optimistic version alone is insufficient for inventory invariants.

## 7. Workflow boundaries

### 7.1 Receiving and putaway

- Receipt header/lines capture expected and actual business quantities.
- Receiving execution is recorded separately and may be partial.
- Accepted quantity physically received posts to a RECEIVING location.
- Rejected-before-custody quantity does not post.
- Damaged goods physically held post to DAMAGED status.
- Putaway execution moves stock from RECEIVING/STAGING to destination atomically.

### 7.2 Transfers

V1 transfer is a single atomic source-to-destination movement. It may be between locations in the same or different warehouses under the same company. There is no in-transit balance, dispatch/receipt split, or partial cross-warehouse receipt in V1. If later required, introduce an explicit staged transfer lifecycle through a new migration; do not reinterpret V1 transfer rows.

### 7.3 Stock count and adjustment

Count sessions capture expected snapshot and counted quantity. Variance is reviewed/approved before posting. The system does not overwrite the balance from a count screen. Approved variance creates adjustment lines and a corresponding inventory transaction.

### 7.4 Outbound

- Outbound order and lines preserve requested quantities.
- Reservations allocate available stock.
- Pick tasks identify source location and quantity.
- Pick confirmation moves stock to a PICKING location and consumes the corresponding reservation; total on-hand is unchanged.
- Shipment/dispatch records actual dispatched quantity.
- Dispatch posts the negative on-hand effect exactly once at the custody boundary.
- Partial picking and partial shipment are supported; requested, reserved, picked, and dispatched quantities remain distinct.

## 8. Posting source traceability

Avoid an unconstrained polymorphic (source_type, source_id) pair as the only source relationship.

Each execution confirmation header has a unique FK to its inventory transaction header. Each execution confirmation line links to its corresponding inventory transaction line through an explicit FK. For a location transfer, one execution line links to both source and destination transaction lines. This preserves referential integrity while allowing partial execution as multiple confirmation records.

The schema catalogue and SQL must implement these explicit links for receiving, putaway, transfer, adjustment, and dispatch. Reservations and pick execution are linked to their outbound order line and source inventory balance.

## 9. Audit and lifecycle

Mutable master and draft/workflow records use created_at, created_by, updated_at, updated_by, and version where appropriate. Posted inventory transactions use posted_at and posted_by and are append-only by application policy. Critical administrative changes may be recorded in audit_events; audit event target identity is intentionally descriptive (entity_type + entity_public_id) and is not a polymorphic operational FK.

All foreign keys use restrictive delete behavior for business history. Users and masters may be deactivated. Historical actor references remain valid. No cascade delete may erase inventory or operational history.

## 10. Physical SQL conventions

- MySQL 8.4, InnoDB, utf8mb4, utf8mb4_0900_ai_ci.
- Signed BIGINT PK/FK types consistently.
- CHAR(36) UUID public_id with unique index on externally addressable records.
- DECIMAL(18,3) quantities.
- DECIMAL(18,6) weight per Base UOM in KG.
- DECIMAL(24,9) location occupancy and weight capacity in KG.
- DATETIME(6) persisted in UTC; store IANA timezone string on Warehouse.
- Explicit NOT NULL for required facts; bounded VARCHAR lengths.
- CHECK constraints for positive/non-negative values and allowed column combinations where MySQL supports them; backend validation remains mandatory for cross-row and workflow invariants.
- Unique constraints use company/warehouse scope as specified above.
- No FLOAT/DOUBLE for quantity or weight.
- No secret, real password, or customer data in seed SQL.

## 11. V1 schema catalogue

The SQL migration must create the following logical groups. Tables may be split/combined only where the relational guarantees and lifecycle described above remain intact.

### Organization and access
companies, users, roles, permissions, user_roles, role_permissions, user_warehouse_access.

### Warehouse structure
warehouses, location_types, locations.

### Item and measurement
uoms, items.

### Inventory core
inventory_statuses, inventory_balances, inventory_transactions, inventory_transaction_lines.

### Inbound and putaway
inbound_receipts, inbound_receipt_lines, receiving_confirmations, receiving_confirmation_lines, putaway_tasks, putaway_confirmations, putaway_confirmation_lines.

### Transfers and stock control
stock_transfers, stock_transfer_lines, transfer_confirmations, transfer_confirmation_lines, stock_counts, stock_count_lines, stock_adjustments, stock_adjustment_lines.

### Outbound
outbound_orders, outbound_order_lines, inventory_reservations, pick_tasks, pick_confirmations, pick_confirmation_lines, shipments, shipment_lines.

### Audit
audit_events.

The V1 table catalogue is deliberately limited to the P0 workflow. It excludes suppliers/customers as full master domains, purchasing/ERP integration, barcode, lot/serial/expiry, license plates, replenishment optimization, wave planning, labor, dock/yard, and multi-tenant SaaS.

## 12. Flyway isolation and legacy protection

The existing application already has legacy Flyway migrations for the current six-table schema. Those migrations must not be reused for the redesigned WMS database.

Use a separate Flyway location, for example:
classpath:db/wms-migration

The new baseline migration is:
V1__create_wms_schema.sql

The WMS schema migration must be tested directly against a genuinely empty disposable MySQL 8.4 database. The new database must have a distinct name and credentials from zirologistics_db. Do not point this migration at the legacy database, do not baseline it, and do not edit existing legacy V1/V2 migrations.

The current JPA entities still describe the legacy schema. Applying the new WMS SQL does not make the current application compatible automatically. The backend entity/repository/service refactor must follow the approved schema, and Hibernate validation against the new database is a later integration gate. Until then, validate the migration independently through Flyway + MySQL Testcontainers.

## 13. Acceptance checklist — one-time completion gate

The database design review is considered complete when all of the following are checked once:
- [ ] DB-14 baseline decisions are reflected in DB-03/DB-04 and no contradictory open decision remains authoritative.
- [ ] SQL V1 creates the full P0 schema on empty MySQL 8.4.
- [ ] All PK/FK types match and all FK targets exist.
- [ ] Unique constraints reflect company/warehouse scopes.
- [ ] CHECK constraints and indexes are present and named consistently.
- [ ] All posting confirmation lines have explicit transaction-line linkage.
- [ ] Reservation and balance identities are consistent.
- [ ] All quantity/weight columns use the frozen precision policy.
- [ ] Required reference rows are deterministic and contain no secrets.
- [ ] Flyway checksum/history and second startup behavior are verified.
- [ ] Integration tests verify posting, rollback, idempotency, transfer conservation, reservation contention, capacity contention, and reconciliation.
- [ ] Legacy zirologistics_db remains untouched.

Once this gate passes, the audit/design phase is CLOSED. Subsequent failures are implementation/test defects or new change requests, not reasons to repeat the completed audit.

---

**Implementation note:** This is the frozen V1 design baseline for preparing SQL. It does not claim the migration has already been executed or that the current backend entities already match it.
