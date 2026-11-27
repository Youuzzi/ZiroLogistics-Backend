# WMS Inventory Posting Core — Design Contract

**Status:** Implemented application-boundary design contract. The inventory posting service and MySQL integration coverage are present; this document records the intended invariants, while current behavior is defined by source and tests.  
**Schema authority:** Frozen V1 plus additive V12 migration.  
**Runtime:** WMS-only modular monolith.  
**Verification:** Disposable MySQL 8.4 Testcontainers only.  
**Scope:** One transactional application boundary for stock balance, immutable ledger, serial lifecycle and tenant audit.

## 1. Purpose and boundary

All future stock-changing workflows (receiving, issuing, transfer, adjustment, returns, damage, opening balance and later reservations) must call one inventory posting application service. They must not write inventory_balances, inventory_ledger or inventory_serials directly.

This service is an internal application boundary, not a generic public stock-delta API. HTTP controllers for receiving, shipping, transfer and adjustment belong to their respective workflow modules and must translate validated business commands into typed posting commands.

No stock mutation is exposed until a workflow owns its business authorization, reference validation and lifecycle.

## 2. Atomicity invariant

A posting is one database transaction containing all of the following:

1. Resolve the authenticated principal against persisted active user, membership and tenant state; check the persisted WMS_INVENTORY_POST permission.
2. Lock the tenant and all affected operational rows in the global lock order below.
3. Validate every item, warehouse, location, lot and serial belongs to the resolved tenant and has a lifecycle state allowed by the movement.
4. Lock or create every affected balance bucket.
5. Apply balance changes with guarded SQL; never write generated available_qty.
6. Append every ledger entry with exact before/after quantities and a shared movement_group_id.
7. Apply any coupled serial lifecycle/location transition.
8. Append one tenant audit event for the movement group.
9. Commit all changes together.

Any validation, constraint, ledger, serial, or audit failure rolls back the entire posting. There is no success response before commit.

## 3. V1 tables and exact numeric rules

Only the existing V1 tables are used:

- inventory_balances is the only balance projection.
- inventory_ledger is append-only movement history.
- inventory_serials stores serial identity and current lifecycle/location.
- inventory_lots, items, warehouses, warehouse_locations, tenants and tenant_memberships are authoritative references.
- tenant_audit_events records the business operation audit event.

Quantity values use Java BigDecimal and must be exactly representable at scale 4, fit DECIMAL(19,4), and be non-zero. Never use floating point. Do not write generated available_qty or lot_key.

Balance bucket identity is tenant + warehouse + location + item + lot + stock status. The database's generated lot_key and unique bucket index remain authoritative.

## 4. Movement shape and semantics

A typed movement command has a UUID request ID and one or more ordered legs. Each leg identifies warehouse, location, item, optional lot, optional serial, stock status and positive quantity magnitude. The application derives signed deltas from the movement type; callers do not submit arbitrary signed deltas.

| Movement type | Required ledger shape | Quantity direction |
|---|---|---|
| RECEIPT | One or more destination legs | Positive |
| RETURN | One or more return-to-stock legs | Positive |
| OPENING_BALANCE | One or more opening legs | Positive |
| ADJUSTMENT_IN | One or more adjustment-in legs | Positive |
| ISSUE | One or more source legs | Negative |
| ADJUSTMENT_OUT | One or more adjustment-out legs | Negative |
| TRANSFER | Matched source/destination pair per item + lot + status | Negative source, positive destination |
| DAMAGE | Matched AVAILABLE-to-DAMAGED pair | Negative AVAILABLE, positive DAMAGED |
| STATUS_CHANGE | Matched source/destination stock-status pair | Negative source status, positive destination status |

For paired movements, total quantity must balance for each item + lot + relevant status transition. A transfer must change physical location; a status change must change stock status; damage must end in DAMAGED. Each movement group uses contiguous entry numbers starting at 1. All ledger rows share the same tenant, request ID and movement group ID.

Movement-specific validation must reject mismatched items, lots, quantities, statuses, source/destination references, duplicate bucket legs, and no-op pairs before writing.

## 5. Lot and serial rules

- Items with tracking method LOT or LOT_AND_SERIAL require a tenant-owned lot on every stock leg.
- Items with tracking method NONE or SERIAL must not be assigned a lot.
- LOT_AND_SERIAL legs require a serial; SERIAL legs require a serial but may omit lot.
- Non-stock items cannot be posted.
- Serial-tracked quantities are exactly one unit per serial ledger entry, matching the V1 CHECK constraint.
- Initial receipt creates the serial identity and its AVAILABLE state in the same transaction as its positive ledger entry and balance update. Duplicate tenant serial numbers are rejected.
- Transfer updates the existing serial's current warehouse/location atomically with both ledger legs and balance buckets.
- Issue changes the serial to SHIPPED and sets shipped_at in the same transaction as the negative ledger entry.
- Damage/status movements update serial status in the same transaction as their paired ledger legs.
- A serial cannot be registered as AVAILABLE, moved, shipped, damaged, quarantined, returned or otherwise transitioned through a master-data-only endpoint.
- Serial status and current location must agree with the posting operation; stale or impossible transitions fail closed.

## 6. Concurrency and lock order

Every writer uses this exact order:

1. Tenant row.
2. All affected warehouse rows, ascending internal ID.
3. All affected location rows, ascending internal ID.
4. All affected item rows, ascending internal ID.
5. All affected lot rows, ascending internal ID.
6. All affected serial rows, ascending internal ID.
7. All affected balance buckets in deterministic tuple order: warehouse, location, item, lot (NULL first), stock status.

Resolve opaque public IDs using tenant-scoped queries, then lock/revalidate rows by internal ID in sorted order. Do not lock a later class before an earlier class. Stage 7 warehouse/location lifecycle mutations already lock tenant → warehouse → location; posting must preserve that compatible prefix so occupancy checks cannot race with stock writes.

Missing balance buckets are created with a unique-key-safe insert and then selected FOR UPDATE. A concurrent create may win; the loser must lock and use the existing row. Do not retry arbitrary transaction fragments. If a deadlock/serialization retry is later introduced, it must replay the complete transaction with the same request ID and a bounded attempt policy.

## 7. Request identity and replay policy

V1 provides unique (tenant_id, request_id, entry_no) ledger entries, but it has no canonical payload hash or posting-request registry.

Therefore:

- The request ID is a caller-supplied UUID used to correlate all entries of one posting.
- A successful request ID is single-use. A second posting using it is rejected as a duplicate; the service does not claim to replay the original response.
- The unique ledger key is the final concurrency guard against duplicate entry numbers.
- Do not claim that V1 protects against a changed payload under a reused request ID with payload-hash comparison.
- A durable payload-hash/idempotency registry is a later additive schema decision, not part of this implementation.

## 8. Authorization and audit

V12 adds the explicit WMS_INVENTORY_POST permission and grants it only to existing system TENANT_OWNER roles. It does not grant posting to all read-only inventory roles.

One INVENTORY_MOVEMENT_POSTED tenant audit event is written per movement group, in the same transaction. Its subject is the movement-group UUID, its operation reference is the request UUID, and its payload contains movement type, entry count and aggregate identifiers/quantities without secrets. Actor identity comes only from the resolved active membership; clients cannot supply actor IDs.

Ledger entries retain the same actor membership and server timestamp. Ledger rows are never updated or deleted by application code.

## 9. Read contracts

Balance and ledger reads always include the resolved tenant predicate. Responses expose public IDs and reference codes, never internal numeric primary keys. Ledger reads are bounded and ordered by occurred_at DESC, id DESC; clients cannot choose tenant IDs or unbounded page sizes. Balance reads use the same tenant-scoped filter model and never imply that available quantity is writable.

## 10. Required verification before merge

Disposable MySQL 8.4 Testcontainers must verify:

- Fresh V1→V12 migration and V11→V12 upgrade, without modifying V1–V11.
- Permission catalogue and owner grant; audit CHECK contains the new event.
- Receipt/issue balance and ledger arithmetic; generated available quantity remains database-derived.
- Transfer and paired status movement all-or-nothing behavior.
- Rollback when any ledger, serial, or audit write fails.
- Lot/item/warehouse/location/serial cross-tenant rejection.
- Serial receipt, transfer, issue, damage and status transition invariants.
- Duplicate request ID rejection and concurrent attempts.
- Concurrent posting to the same balance bucket without lost updates or negative stock.
- Warehouse/location status transition cannot race a stock posting.
- BigDecimal precision/scale rejection and insufficient-stock rejection.

No user database is used or accessed by these tests.
