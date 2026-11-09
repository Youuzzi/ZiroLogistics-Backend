# WMS Inbound, Internal Movement & Outbound Workflow Model

- **Document ID:** DB-08
- **Version:** 0.1
- **Status:** Proposed — workflow and posting boundaries for review
- **Parent:** DB-01 WMS Database Architecture
- **Depends on:** DB-02 Business Domain & Capability Model; DB-03 Logical ERD; DB-05 Inventory Integrity; DB-06 Location Model
- **Product stage:** Initial release for small and medium warehouses

## 1. Purpose

This document defines the operational lifecycle that the first WMS database must be able to represent for inbound receiving, putaway, internal movement, stock control, and outbound dispatch.

It separates a business request/document from work execution and from the inventory posting that records the physical effect. The final SQL schema must reflect these boundaries without forcing a small warehouse to operate an unnecessarily complex workflow engine.

## 2. Common document lifecycle

A business document may pass through the following conceptual states:

- DRAFT: being prepared; no inventory effect.
- OPEN/RELEASED: accepted for operational execution; no inventory effect by itself.
- IN_PROGRESS: some execution has occurred; partial quantities may be recorded.
- COMPLETED: required execution is complete; posting status must be explicit.
- CANCELLED: no further execution is allowed; any prior posted effects require reversal, not deletion.

A document's business status and its inventory posting status are not necessarily the same. A document may be operationally complete while its posting is pending, or partially posted while remaining open for further execution.

Do not add these exact state values to every table automatically. Each workflow must define its own valid transitions, partial completion, cancellation, and reversal rules.

## 3. Inbound receiving

### 3.1 Business purpose

Inbound records goods arriving at a warehouse and establishes what was physically received, accepted, rejected, damaged, or discrepant.

The initial release should support receiving without requiring advanced ASN, supplier integration, dock scheduling, or quality-management modules.

### 3.2 Proposed document structure

- Inbound Receipt header: warehouse, reference, supplier/external reference if applicable, expected date, status, and audit metadata.
- Inbound Receipt Line: item, expected quantity (optional), received quantity, accepted quantity, rejected/damaged quantity, and UOM.
- Receiving Confirmation: actor, timestamp, actual quantities, and discrepancy/reason details.
- Putaway Task: source receiving/staging location, destination location, item, and quantity to move.
- Putaway Confirmation: actual moved quantity, actor, timestamp, and exception details.

Partial receipt and partial putaway should be representable even if the first UI initially offers a simple full-quantity flow. The database should not assume one receipt line can only be executed once.

### 3.3 Proposed posting boundary

**Recommended starting point:** model receiving/staging as a real inventory-bearing location. When a receipt is physically confirmed and accepted, post the accepted quantity into that location. Putaway then posts a location-to-location movement from receiving/staging to the destination storage location.

This approach makes physical custody and stock location explicit. It also allows a warehouse to hold received goods temporarily before storage.

Rejected or damaged goods should be represented according to the agreed disposition policy. If damaged goods remain physically inside the warehouse, they remain on-hand in a non-allocatable status. If they are refused and never enter warehouse custody, they should not increase on-hand.

This recommendation remains open until the user confirms how receiving is performed in the target warehouses.

## 4. Putaway

Putaway moves accepted stock from a receiving/staging location to an eligible destination.

Required rules:
- source and destination belong to the same warehouse for ordinary putaway;
- item and quantity are valid and use approved UOM precision;
- source has sufficient on-hand quantity in the relevant status;
- destination is active and eligible for storage;
- capacity is checked if enabled;
- partial putaway is supported if the operational process permits it;
- posting changes location distribution but not total warehouse/company on-hand;
- source and destination effects commit atomically.

A task instruction is not proof of movement. Only a confirmed execution can post inventory.

## 5. Internal transfer

### 5.1 Within-warehouse transfer

For a simple immediate move:
1. Create/release transfer request.
2. Validate source, destination, item, status, quantity, and capacity.
3. Confirm the physical move.
4. Post source decrease and destination increase under one transaction identity.
5. Mark the executed quantity complete.

Net on-hand change is zero.

### 5.2 Cross-warehouse transfer

Two candidate models exist:

**Option A — immediate logical transfer:** source and destination effects post together. This is simpler, but only correct when the business treats the transfer as instantaneous or does not need visibility while goods are travelling.

**Option B — staged dispatch and receipt:** dispatch reduces source stock and creates an in-transit quantity/state; destination receipt reduces in-transit and increases destination stock. This is more accurate where transport takes time or custody changes in stages.

Recommendation: keep within-warehouse transfer simple in P0. For cross-warehouse movement, use staged dispatch/receipt if the product must show goods in transit or support partial receipt. Do not model in-transit as a normal physical storage bin unless operations require an actual staging location.

## 6. Stock count and adjustment

A stock count records physical observation; it must not overwrite balances directly.

Proposed flow:
1. Create count session with warehouse/location/item scope.
2. Define whether system quantity is visible to the counter (blind or non-blind count).
3. Record one or more count observations.
4. Reconcile expected and counted quantity.
5. Review/recount discrepancies where required.
6. Approve variance.
7. Post a controlled adjustment with reason and actor.
8. Preserve the count result and adjustment history.

A count session may cover one location, selected items, or a wider warehouse scope. The first release can use a simple scope model but should not hard-code only full-warehouse counts.

Open questions include count freeze behavior, blind count, recount approval, and whether adjustments require a second approver.

## 7. Outbound fulfilment

### 7.1 Business purpose

Outbound represents demand to remove goods from warehouse custody and records picking and dispatch evidence.

The first release should support a straightforward order-to-pick-to-dispatch process without mandatory wave planning, packing optimization, or carrier integrations.

### 7.2 Proposed document structure

- Outbound Order header: warehouse, external/customer reference if used, status, and audit metadata.
- Outbound Order Line: item, requested quantity, and UOM.
- Reservation: optional commitment against eligible inventory.
- Pick Task: source location, item, quantity to pick, and execution status.
- Pick Confirmation: actual picked quantity, actor, timestamp, and exception details.
- Shipment/Dispatch: dispatch identity, date/time, and destination/reference metadata as needed.
- Shipment Line: actual dispatched quantity linked to the originating order line.

Requested, reserved, picked, and dispatched quantities must remain separate. A short pick or partial shipment must not require overwriting the original requested quantity.

### 7.3 Proposed posting boundary

**Recommended starting point:** reduce on-hand when goods are confirmed as dispatched beyond the defined warehouse custody boundary. Picking moves stock to a picking/packing/dispatch location if those are modeled as inventory-bearing locations; it does not reduce total on-hand.

If the first release chooses a simpler issue workflow without formal picking locations, the dispatch confirmation remains the stock-reduction boundary. The schema must still preserve requested and actual dispatched quantities separately.

### 7.4 Reservation

Reservation is a commitment, not a physical movement. It reduces available quantity but does not reduce on-hand or physical occupancy.

The first release may:
- include formal reservations before picking; or
- use a simpler atomic availability check at pick/dispatch time.

If reservation is included, the schema must track reserved quantity by the same dimensions used to determine eligible inventory and prevent over-allocation under concurrency.

## 8. Cancellation and reversal

- A draft document with no execution may be cancelled without inventory posting.
- A released but unexecuted document may be cancelled under its workflow rules.
- A document with partial execution may cancel only the unexecuted remainder; posted quantities remain in history.
- A posted movement is corrected by a compensating/reversal posting.
- Reversal must reference the original posting and be bounded by the un-reversed quantity.
- Reversal must not erase the original ledger evidence.
- Repeated cancellation/reversal requests must be idempotent.

## 9. Required document-to-posting traceability

Every posted inventory effect must resolve to:
- the originating business document and line where applicable;
- the execution confirmation that authorized the physical effect;
- the common posting/transaction identity;
- the actor and posting timestamp;
- the idempotency identity;
- the affected item, location, status, and quantity.

Avoid free-form source type/source ID pairs as the only relationship mechanism unless the loss of database-enforced referential integrity is explicitly accepted.

## 10. Required workflow tests

- draft receipt creates no on-hand;
- accepted receiving posts once to the receiving/staging location;
- rejected goods do not increase on-hand if they never enter custody;
- damaged goods held physically remain on-hand but are not normally allocatable;
- putaway changes location distribution without changing total on-hand;
- partial receiving and putaway preserve remaining quantities;
- source shortage prevents putaway/transfer;
- destination capacity is enforced during putaway;
- within-warehouse transfer conserves quantity;
- staged cross-warehouse transfer conserves source + in-transit + destination;
- stock count does not change on-hand until approved variance is posted;
- partial pick and dispatch retain requested versus actual quantities;
- pick does not reduce total on-hand unless the defined custody boundary says so;
- dispatch reduces on-hand exactly once;
- cancellation of a partially executed document preserves posted effects;
- reversal creates compensating history and cannot be repeated beyond the original effect.

## 11. Decisions required before SQL

| ID | Decision | Recommended starting point |
|---|---|---|
| DB8-01 | Receiving posting point | Post accepted receipt to a real receiving/staging location |
| DB8-02 | Damaged/rejected goods | Distinguish goods refused before custody from damaged goods physically held |
| DB8-03 | Putaway | Treat putaway as a confirmed location-to-location movement |
| DB8-04 | Cross-warehouse transfer | Use staged dispatch/receipt if in-transit visibility or partial receipt is needed |
| DB8-05 | Stock count | Include basic count and approved variance adjustment in P0 |
| DB8-06 | Count controls | Decide blind count, freeze, recount, and approval needs |
| DB8-07 | Outbound | Preserve requested, picked, and dispatched quantities separately |
| DB8-08 | Dispatch boundary | Reduce on-hand when goods leave warehouse custody |
| DB8-09 | Reservation | Decide formal reservation versus atomic availability check at pick |
| DB8-10 | Partial execution | Confirm partial receiving, putaway, transfer, picking, and dispatch support |

## 12. Readiness gate

The workflow schema is ready when:
- each document lifecycle and allowed transition is defined;
- the physical custody boundary for receiving and dispatch is agreed;
- partial execution rules are agreed;
- transfer and in-transit semantics are agreed;
- reservation behavior is agreed;
- cancellation and reversal rules are specified;
- source-document to posting traceability is enforceable;
- required integration tests are mapped to the chosen workflow.

---

**Public documentation note:** This is a proposed operational workflow model. It does not claim that the workflows are implemented in the current application.