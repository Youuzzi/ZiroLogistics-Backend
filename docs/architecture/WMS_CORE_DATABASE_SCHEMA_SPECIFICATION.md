# WMS Core Database Schema Specification

- **Document ID:** DB-04
- **Version:** 0.1
- **Status:** Proposed — physical design standards, not SQL-ready
- **Parent:** DB-01 WMS Database Architecture
- **Depends on:** DB-02 Business Domain & Capability Model; DB-03 Logical ERD
- **Database target:** MySQL 8.4 LTS / InnoDB
- **Migration tool:** Flyway

## 1. Purpose

This document defines physical database design standards and candidate schema boundaries for the new WMS database.

It is deliberately not a final table-by-table SQL specification. Several business decisions in DB-02 and DB-03 remain open. Column definitions, exact keys, indexes, constraints, and migration scripts must not be frozen until those decisions are resolved.

The new database will be created from reviewed Flyway migrations on a fresh schema. The existing local database remains an archive/reference and must not be reset, adopted, or modified as part of this work.

## 2. Design authority and existing schema

The current main-branch V1 migration is a reference for existing application behavior, not an automatic template for the redesigned schema.

The current V1 defines six principal tables: users, warehouses, items, bins, inventory_stocks, and inventory_ledger.

The redesigned model is expected to introduce explicit company ownership, addressable warehouse locations, controlled UOM/status concepts, and explicit operational documents. The old bins model should not be copied unchanged if the approved location model uses a generic location hierarchy.

The V1 migration contains broad VARCHAR lengths, DECIMAL(38,2) fields, mixed timestamp conventions, and a redundant non-unique SKU index alongside a unique SKU index. These are review findings, not standards to preserve automatically.

The existing V2 decimal migration is a candidate input only. Its precision decisions must be reconciled with the approved UOM, quantity, and physical-measurement contract before reuse.

## 3. Physical design principles

1. Use InnoDB for transactional tables and foreign-key enforcement.
2. Use utf8mb4 with a deliberate collation policy.
3. Use explicit NOT NULL constraints for required business facts.
4. Define uniqueness at the correct ownership scope.
5. Use foreign keys for relational integrity; do not rely only on application checks.
6. Use restrictive delete behavior for posted operational history.
7. Prefer deactivation over deleting referenced master data.
8. Use database CHECK constraints where supported and appropriate, alongside application-level domain validation.
9. Avoid duplicate indexes that add write cost without query benefit.
10. Index real query and join paths, guided by explain plans and representative workloads.
11. Keep the balance table optimized for current-state reads and safe atomic updates.
12. Preserve movement history as durable evidence; do not update or delete posted history in normal flows.
13. Store exact quantities using DECIMAL, never FLOAT or DOUBLE.
14. Do not store derived values in multiple independently writable places without a reconciliation rule.
15. Keep P0 schema compact; future extension tables must have a documented use case.

## 4. Naming conventions — proposed

| Object | Convention | Example |
|---|---|---|
| Table | plural snake_case | inventory_balances |
| Primary key | id | id |
| Foreign key | referenced singular name + _id | warehouse_id |
| Unique constraint | uk_<table>_<purpose> | uk_items_company_sku |
| Foreign key constraint | fk_<table>_<referenced_table> | fk_locations_warehouses |
| Check constraint | chk_<table>_<rule> | chk_inventory_balances_nonnegative |
| Index | idx_<table>_<query_path> | idx_inventory_balances_location |
| Flyway migration | V<number>__<description>.sql | V1__create_core_schema.sql |

Names should describe business meaning and remain stable across migrations. Avoid abbreviations unless they are standard WMS terms and defined in the domain glossary.

## 5. Identifier strategy — decision pending

The current application uses BIGINT auto-increment primary keys and public_id values. The new design should explicitly distinguish:
- internal relational primary keys;
- externally exposed stable identifiers;
- human-readable business codes such as warehouse code, SKU, and location code.

Candidate options:
1. BIGINT internal PK + UUID public identifier;
2. UUID primary keys throughout;
3. BIGINT primary keys with carefully scoped business identifiers where external exposure is not required.

**Recommendation for evaluation:** retain efficient BIGINT relational keys and use a separate opaque public identifier for API-facing records. The UUID storage format and generation policy must be selected before DB-04 is finalized. Public IDs must not be treated as authorization tokens.

## 6. Data type policy — proposed baseline

| Business value | Candidate MySQL type | Notes |
|---|---|---|
| Internal identifier | BIGINT | Use consistent signedness across PK/FK |
| Public identifier | CHAR(36), BINARY(16), or equivalent | Select format and generation policy |
| Short code | VARCHAR with bounded length | Length based on business rules, not default 255 |
| Name | VARCHAR with bounded length | Define maximum by domain |
| Long description/notes | TEXT where justified | Avoid TEXT for indexed identifiers |
| Boolean state | BOOLEAN/TINYINT(1) | Define defaults and NOT NULL consistently |
| Quantity / measurement | DECIMAL(p,s) | Exact precision and scale in DB-07/DB-05 |
| Money | DECIMAL(p,s) | Currency and scale rules if commercial amounts are introduced |
| Date-only business date | DATE | Do not substitute timestamp |
| Event timestamp | DATETIME(6) or TIMESTAMP(6) | Choose timezone convention consistently |
| Optimistic version | BIGINT NOT NULL DEFAULT 0 | Apply where concurrent updates require it |

Do not finalize decimal precision based only on the existing V2 migration. Quantity, conversion factors, unit weight, and derived physical occupancy may require different scales.

For timestamps, choose one storage convention (recommended: UTC instants at application boundaries) and document conversion to local warehouse/business time. Business operating date and timestamp are different concepts.

## 7. Ownership and uniqueness

The initial ownership path is Company → Warehouse → Location → Inventory.

Expected uniqueness scopes to validate:
- company code: unique within the product's company identity scope;
- warehouse code: unique within company;
- location code: unique within warehouse;
- item SKU: unique within company unless product requirements explicitly allow duplicates;
- user email: unique according to the authentication identity policy;
- external reference: unique within its source/integration scope;
- idempotency key: unique within a documented actor/operation/company scope.

Do not use global uniqueness when the actual business rule is company- or warehouse-scoped. Conversely, do not weaken a globally required identity into a local uniqueness constraint.

Composite foreign keys may be needed to prevent cross-company or cross-warehouse references, for example ensuring a location's warehouse belongs to the same company as its operational document. The final design should prefer database-enforced ownership consistency where practical.

## 8. Inventory schema requirements

The inventory schema must not be finalized until DB-05 approves the inventory dimension and posting model.

At minimum, the logical model requires:
- an inventory balance keyed by item, location, and approved inventory status;
- a durable posting identity for each stock-changing business operation;
- movement lines with signed exact quantity effects;
- source-document traceability;
- idempotent posting;
- atomic balance and history updates;
- controlled reversal/correction semantics.

The schema must define how it prevents:
- negative stock under the default policy;
- duplicate posting on retries;
- source/destination imbalance during transfers;
- cross-warehouse ownership violations;
- status changes that accidentally alter physical occupancy;
- concurrent update anomalies.

A balance row should not be duplicated for the same approved inventory dimension. The database should enforce the selected composite uniqueness key.

## 9. Master-data lifecycle

- Prefer active/inactive lifecycle fields over destructive deletion for referenced master records.
- Required master records cannot be deactivated while open operational work depends on them unless the workflow has a safe transition.
- Historical documents must preserve the identity of the master data used at posting time.
- Changes to measurement semantics that affect existing stock must be blocked or versioned.
- Location hierarchy updates must not create cycles or move stock implicitly.
- Code changes must preserve historical references and uniqueness.

The final schema must specify whether a record is hard-deletable, soft-deletable, or immutable after first use.

## 10. Audit and concurrency

Critical operational tables should include a consistent creation/update audit strategy. The exact actor reference should use a stable user identity, not a mutable display name.

Optimistic locking is appropriate for editable master and workflow records where stale updates must be detected. Inventory posting additionally requires transaction-safe locking or atomic conditional updates; an ORM version column alone is not sufficient to protect multi-row stock invariants.

Posted transaction records should be immutable through normal application operations. Corrections should be represented by explicit compensating records.

## 11. Index strategy

Indexes must be derived from actual access patterns. Candidate access paths include:
- item lookup by company and SKU;
- warehouse lookup by company and code;
- location lookup by warehouse and location code;
- balance lookup by item/location/status;
- balance listing by warehouse/location/status;
- transaction history by item, location, and posting time;
- inbound/outbound documents by warehouse, status, and document date;
- open tasks by warehouse, status, and priority;
- idempotency lookup by its unique scope.

For every index, document the query or constraint it supports. Avoid adding a single-column index when a suitable composite index already has the same leading column, unless query evidence justifies it.

## 12. Migration and seed-data boundary

- V1 of the new database must create the complete approved baseline schema on an empty database.
- Later schema changes must be additive or use reviewed expand/contract migrations where practical.
- Applied migrations are immutable; corrections require a new migration.
- Flyway history must be the authoritative record of applied schema versions.
- Reference data required for valid operation must be inserted deterministically and idempotently.
- Demo/sample data must be separated from required reference data.
- Secrets and environment-specific credentials must never be stored in migrations or committed configuration.
- The existing database without Flyway history requires a separately reviewed adoption/baseline plan; it must not be silently treated as a fresh V1 installation.

## 13. Candidate P0 table groups

The following is a planning catalogue, not an approved final table list.

| Group | Candidate tables | Gate |
|---|---|---|
| Company & access | companies, users, roles, permissions, user_roles, user_warehouse_access | Confirm identity and role model |
| Warehouse | warehouses, location_types, locations | Confirm location hierarchy |
| Item & UOM | items, uoms, item_uom_conversions | Confirm base-UOM/conversion scope |
| Inventory | inventory_statuses, inventory_balances, inventory_transactions, inventory_transaction_lines | Confirm dimensions and ledger pattern |
| Inbound | inbound_receipts, inbound_receipt_lines, receiving_confirmations, putaway_tasks, putaway_confirmations | Confirm posting boundary and partial receipt |
| Internal movement | stock_transfers, stock_transfer_lines, transfer_confirmations | Confirm in-transit workflow |
| Stock control | stock_counts, stock_count_lines, stock_adjustments, stock_adjustment_lines | Confirm count approval and variance rules |
| Outbound | outbound_orders, outbound_order_lines, inventory_reservations, pick_tasks, pick_confirmations, shipments, shipment_lines | Confirm reservation and dispatch lifecycle |
| Governance | audit_events or equivalent, idempotency records or equivalent | Confirm audit and idempotency design |

Some concepts may be implemented as state/history tables or transaction subtypes rather than separate tables. The final design should avoid redundant representations.

## 14. SQL readiness checklist

The physical SQL design is ready only when:
- DB-02 P0 capabilities are approved;
- DB-03 entities and cardinalities are approved;
- inventory dimensions, status semantics, and posting model are approved;
- inbound, transfer, count, and outbound lifecycle boundaries are approved;
- UOM and decimal precision rules are approved;
- identifier and uniqueness scopes are approved;
- lifecycle, audit, and concurrency rules are specified;
- migration and reference-data strategy is reviewed;
- schema can be tested from an empty MySQL 8.4 database using Flyway.

Until then, SQL implementation is intentionally blocked to avoid encoding unresolved assumptions into an irreversible migration history.

---

**Public documentation note:** This document describes proposed database design standards and candidate schema groups. It does not claim that the proposed schema exists or is implemented.