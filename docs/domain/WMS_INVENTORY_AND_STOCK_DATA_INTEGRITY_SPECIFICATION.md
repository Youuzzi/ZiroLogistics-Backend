# WMS Inventory & Stock Data Integrity Specification

- **Document ID:** DB-05
- **Version:** 0.1
- **Status:** Proposed — integrity contract for review
- **Parent:** DB-01 WMS Database Architecture
- **Depends on:** DB-02 Business Domain & Capability Model; DB-03 Logical ERD
- **Product stage:** Initial release for small and medium warehouses

## 1. Purpose

This document defines the integrity rules that the new WMS inventory model must preserve. It is a domain contract for the future schema, services, and integration tests.

The central requirement is that recorded stock, physical movement, reservation, and historical evidence must remain consistent under normal operations, retries, failures, and concurrent requests.

This document does not finalize table names or SQL columns. Inventory dimensions, status semantics, posting boundaries, and quantity precision must be approved before implementation.

## 2. Inventory concepts that must remain distinct

| Concept | Meaning | Does it change physical on-hand? |
|---|---|---|
| On-hand quantity | Quantity physically recorded within warehouse custody | Yes, when physical stock enters/leaves or changes location |
| Available quantity | Quantity eligible to be promised/allocated | Not by itself |
| Reserved quantity | On-hand quantity committed to demand | No |
| Damaged quantity | Physical stock recorded as damaged | No, until physically removed/disposed |
| Blocked/quarantined quantity | Physical stock held from normal use | No |
| In-transit quantity | Stock dispatched from source but not yet received at destination | It changes location/custody state, not total company stock |
| Inventory ledger | Durable history of posted quantity effects | It records changes; it is not itself a physical quantity |

Reservation must not be modeled as a physical stock status named RESERVED unless a specific operational rule requires that representation. A reservation is an allocation against eligible on-hand stock; it does not mean the goods have physically moved.

## 3. Inventory dimensions

### 3.1 P0 candidate balance identity

The minimum proposed inventory balance identity is:

- item;
- addressable location;
- inventory status.

The candidate uniqueness key is therefore (item_id, location_id, inventory_status_id).

Every location belongs to one warehouse, so warehouse can be resolved through location. A warehouse_id duplicate on every balance row should be avoided unless query/performance evidence justifies it and consistency is enforced.

### 3.2 Deferred dimensions

Lot, serial, expiry, license plate/handling unit, owner, and quality attributes are not mandatory in the first release unless the product's target customer requires them.

If any such dimension is approved, it must be included consistently in balance identity, movement lines, reservation eligibility, pick allocation, and reconciliation. It is unsafe to add tracking only to the item master while balances remain untracked.

## 4. On-hand and availability rules

Proposed baseline:

1. On-hand is the sum of physical inventory quantities across the defined physical statuses and locations within company custody.
2. Available quantity is on-hand quantity in statuses eligible for allocation, less active reservations.
3. Reserved quantity remains part of on-hand.
4. Damaged and quarantined/blocked stock remains on-hand while physically held, but is excluded from normal allocation.
5. A reservation cannot exceed the eligible unreserved quantity under the default policy.
6. Dispatch reduces on-hand only when the goods leave the defined warehouse custody boundary.
7. A location-to-location movement changes the distribution of stock, not total on-hand.
8. A status-only change does not change total physical quantity or physical occupancy.
9. A physical disposal/write-off reduces on-hand through an explicit posted adjustment/disposition.
10. The default policy rejects negative on-hand and negative available quantity.

The exact status catalogue and whether blocked stock is available for special workflows remain open decisions.

## 5. Movement and ledger model

Every stock-changing operation must create durable movement evidence.

A movement line should logically capture:
- item and inventory dimensions;
- source and destination location/status where relevant;
- signed quantity effect;
- transaction UOM and normalized base quantity where conversion is used;
- business transaction identity;
- posting timestamp and actor;
- reason/reference and reversal linkage where applicable.

A transfer may be represented as paired negative/positive effects under one transaction identity or as a single movement record with source and destination. The chosen representation must make conservation and audit queries unambiguous.

A maintained balance is a current-state projection. The ledger/posted transaction history is the evidence used to explain how the balance changed. Do not allow both a ledger table and transaction-line table to become separate authoritative writable histories.

### 5.1 Append-only and correction

- Posted movements are immutable through ordinary application operations.
- A mistake is corrected through a compensating/reversal posting.
- A reversal references the original posting and records a reason and actor.
- Reversals cannot exceed the un-reversed quantity/effect of the original posting.
- Draft or unposted documents may be edited under their own lifecycle rules.
- Deleting a posted transaction is prohibited.

## 6. Idempotency and atomic posting

Every posting request must carry a stable idempotency identity scoped to the operation and company (and, where appropriate, the actor or client).

Required behavior:
1. First valid request posts the operation once.
2. A retry with the same key and same semantic payload returns the original outcome or a safe duplicate result.
3. Reuse of the same key with a different payload is rejected.
4. Two concurrent requests with the same key cannot both post.
5. Posting identity, movement lines, balance updates, source-document state, and idempotency outcome commit atomically.
6. A failed transaction leaves no partial balance or ledger effect.
7. Idempotency retention must cover the supported retry window and operational recovery requirements.

A unique database constraint must enforce the selected idempotency scope. Application-level existence checks alone are insufficient under concurrency.

## 7. Concurrency and negative-stock protection

Inventory operations may compete for the same balance. The implementation must use a transaction-safe strategy such as row locking, atomic conditional updates, or a documented combination.

The strategy must guarantee:
- two concurrent outbound requests cannot both consume the same available stock;
- a transfer cannot remove more than the source balance;
- a reservation cannot over-allocate stock;
- balance and ledger changes commit or roll back together;
- lock ordering is consistent to reduce deadlocks;
- deadlock/serialization retries are bounded and safe under idempotency.

An ORM optimistic version column alone does not protect multi-row invariants or duplicate posting. The final service design and MySQL integration tests must prove the selected strategy.

## 8. Transfer conservation

A transfer is a movement, not a stock creation or destruction.

For a completed immediate transfer of quantity Q:
- source effect = -Q;
- destination effect = +Q;
- net company on-hand effect = 0.

For a staged cross-warehouse transfer:
- dispatch reduces source-location stock and increases in-transit stock;
- destination receipt reduces in-transit stock and increases destination stock;
- the total across source, in-transit, and destination remains conserved.

Partial dispatch and partial receipt must preserve the same conservation rule for each posted quantity.

A transfer must have one traceable operation identity and must not be represented by two unrelated requests that can succeed independently without reconciliation.

## 9. Physical occupancy and capacity

Physical occupancy is distinct from available quantity.

Proposed rule:
- physical occupancy includes every stock status physically present in a location, including available, damaged, and quarantined/blocked stock;
- reservation does not change physical occupancy;
- status-only changes do not change physical occupancy;
- location-to-location movement decreases source occupancy and increases destination occupancy;
- dispatch decreases warehouse occupancy only at the defined custody boundary;
- in-transit stock is excluded from storage-location occupancy unless it is physically held in a modeled staging location.

Capacity policy must define whether limits are based on weight, volume, pallet positions, or a combination. A single generic capacity number is not sufficient if units are not explicit.

Where physical weight is used, the model must define the semantics and precision of unit weight, quantity, conversion, and derived occupancy. Capacity checks must run within a concurrency-safe transaction so two simultaneous putaways cannot exceed capacity.

The existing V2 precision proposal is a candidate only. Final scales belong in DB-07 after supported UOMs and measurement semantics are agreed.

## 10. Reconciliation requirements

The system must support at least these reconciliation checks:

1. Balance versus sum of posted movement effects by inventory dimension.
2. Source/destination conservation for transfers.
3. Active reservation totals versus eligible on-hand stock.
4. Physical occupancy versus the sum of physically present stock weight/volume.
5. Posted source-document quantities versus linked posting quantities.
6. No duplicate idempotency identity within its scope.
7. No balance row duplicated for the same inventory dimension.
8. No negative balance or invalid status/location combination.
9. No posted movement referencing deactivated or cross-company master data contrary to policy.

Reconciliation results should identify discrepancies; automatic correction must not silently mutate operational stock. Corrections require a controlled adjustment or reversal workflow.

## 11. Required integration test scenarios

The new database and backend must be tested against MySQL 8.4, preferably with Testcontainers, for:

- first posting and exact balance/ledger result;
- duplicate retry with identical payload;
- idempotency-key reuse with different payload;
- transaction rollback after an injected failure between movement and balance writes;
- two concurrent outbound requests competing for insufficient stock;
- two concurrent reservations competing for the same stock;
- transfer source/destination conservation;
- partial transfer and staged in-transit receipt, if approved;
- status change without physical quantity/occupancy change;
- damaged/blocked stock excluded from normal availability;
- capacity boundary and concurrent putaway;
- reversal and repeated reversal rejection;
- reconciliation of balances against posted history;
- cross-company and cross-warehouse reference rejection.

Tests must assert database state after the transaction, not only HTTP response codes.

## 12. Decisions required before SQL

| ID | Decision | Recommended starting point |
|---|---|---|
| DB5-01 | Inventory status catalogue | Start small: AVAILABLE, DAMAGED, and BLOCKED/QUARANTINED; validate whether the last two are separate |
| DB5-02 | Reservation | Keep reservation separate from physical stock status |
| DB5-03 | Negative stock | Reject negative on-hand and over-reservation by default |
| DB5-04 | Ledger representation | One authoritative immutable posting history; avoid duplicate writable histories |
| DB5-05 | Transfer representation | One transaction identity with balanced source/destination effects |
| DB5-06 | Occupancy | Count all physically present statuses; reservation/status change alone does not alter occupancy |
| DB5-07 | Capacity dimensions | Decide whether first release needs weight only, volume, or simple location capacity |
| DB5-08 | Posting boundary | Align inbound/outbound custody boundaries with DB-08 |
| DB5-09 | Tracking dimensions | Keep lot/serial/expiry/LP out of P0 unless a real target workflow requires them |

## 13. Readiness gate

Inventory SQL is ready only after:
- inventory dimensions and status semantics are approved;
- on-hand, available, reserved, and in-transit definitions are accepted;
- inbound, transfer, and outbound posting boundaries are settled;
- ledger and posting identity design is selected;
- quantity and physical occupancy precision is approved;
- concurrency strategy and negative-stock policy are testable;
- reconciliation queries and integration test scenarios are defined.

---

**Public documentation note:** This is a proposed integrity contract. It describes intended rules and tests, not verified behavior of the current application.