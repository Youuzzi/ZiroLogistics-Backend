# Inventory Occupancy Integrity Specification

**Project:** ZiroLogistics WMS  
**Document ID:** WMS-DOMAIN-OCC-001  
**Version:** 0.1  
**Status:** Draft — design baseline; implementation not approved  
**Last updated:** 2026-11-09

> This document defines the intended integrity contract. It does not authorize production source changes, database migrations, deployed-data corrections, or deployment.

## 1. Purpose and relationship

Define the rules for consistency between inventory stock, physical bin occupancy, bin capacity, and inventory ledger history.

This document complements [UOM & Quantity Precision Specification](UOM_AND_QUANTITY_PRECISION_SPEC.md). Quantity precision, Base UOM, and the meaning of weightPerUnit must follow that specification.

## 2. Domain invariants

### 2.1 Occupancy represents physical weight

A bin's occupancy represents the physical weight of all inventory physically present in that bin, regardless of stock status.

The initial status set is AVAILABLE, RESERVED, and DAMAGED. A status-only transition does not change physical occupancy. Physical receipt, removal, or relocation does.

### 2.2 Canonical calculation

For a bin:

`calculatedOccupancyKg = SUM(stock.quantity × item.weightPerUnitKg)`

The aggregation includes every stock record in the bin and uses the canonical KG-per-one-Base-UOM definition from the UOM specification.

Use Java BigDecimal arithmetic. Do not use floating-point arithmetic or silently round quantities. The final weight precision and rounding boundary must be explicitly defined and consistently applied by inbound, outbound, transfer, and reconciliation.

The persisted currentWeightOccupancy is a transactionally maintained aggregate for operational capacity checks. It is not an independent source of physical truth; it must be reconcilable against stock records.

### 2.3 Capacity invariant

For a capacity-managed bin: `0 <= currentWeightOccupancy <= maxWeightCapacity`.

A capacity value of zero must not be interpreted as unlimited capacity. Unknown or unverified capacity must be treated as an operational data-quality issue, not guessed from current stock or a UI default.

## 3. Movement transaction contract

All stock, occupancy, and ledger writes for one movement must commit or roll back together.

### 3.1 Inbound

1. Resolve and pessimistically lock the item as required by the existing transaction design.
2. Validate quantity against the item's Base UOM policy.
3. Check idempotency.
4. Lock the destination bin.
5. Calculate incoming physical weight using the shared weight policy.
6. Validate projected occupancy against capacity.
7. Increase AVAILABLE stock by the exact accepted quantity.
8. Increase bin occupancy by the corresponding physical weight.
9. Write the inventory ledger entry.
10. Commit atomically.

### 3.2 Outbound

1. Resolve and lock the item.
2. Validate Base UOM quantity and idempotency.
3. Lock the source bin.
4. Resolve AVAILABLE stock and validate sufficient quantity.
5. Calculate outgoing physical weight using the shared weight policy.
6. Reject an occupancy result below zero or an inconsistent stock/weight state.
7. Decrease stock and occupancy.
8. Write the inventory ledger entry.
9. Commit atomically.

### 3.3 Transfer

1. Resolve and lock the item.
2. Validate Base UOM quantity and idempotency.
3. Lock both bins in a deterministic, stable order.
4. Validate source AVAILABLE quantity.
5. Calculate physical transfer weight once using the shared policy.
6. Validate source occupancy and destination capacity.
7. Decrease source stock and occupancy.
8. Increase destination stock and occupancy.
9. Write paired TRANSFER_OUT and TRANSFER_IN ledger entries.
10. Commit both sides atomically.

A transfer must never leave only one side persisted.

## 4. Locking and concurrency

The current source uses pessimistic item and bin repository locks; transfer obtains bin locks in stable bin-code order. This is a sound starting design, but source inspection alone does not prove runtime concurrency guarantees.

Required MySQL integration evidence:

- Concurrent inbound operations targeting the same bin cannot exceed capacity.
- Concurrent outbound operations cannot oversell AVAILABLE stock.
- Different-SKU movements targeting the same bin serialize occupancy changes.
- Concurrent transfers cannot create negative source occupancy or exceed destination capacity.
- Transfers locking the same pair in opposite directions do not introduce avoidable deadlocks.
- Duplicate request IDs do not create duplicate movements.

Keep idempotency checks and locks in a transactionally safe order. Any retry behavior and unique-key conflict handling must be tested against MySQL.

## 5. Reconciliation

A reconciliation process must independently calculate expected occupancy from stock and item weight, without trusting the stored occupancy value.

For each bin, report warehouse and bin identifiers, stored occupancy, calculated occupancy, difference, capacity and utilization where capacity is verified and positive, stock record count, quantity and weight totals by status, validation result, and supporting details.

The initial reconciliation capability must be read-only. It must not update occupancy, stock, capacity, or ledger records.

### 5.1 Result classifications

| Result | Meaning |
|---|---|
| MATCH | Stored and calculated occupancy agree within the explicitly approved precision policy |
| OCCUPANCY_MISMATCH | Stored occupancy differs from calculated occupancy |
| CAPACITY_UNKNOWN | Physical capacity has not been verified |
| CAPACITY_EXCEEDED | Calculated occupancy exceeds verified capacity |
| INVALID_STOCK_DATA | Invalid quantity, weight, UOM, or related source data prevents a trustworthy calculation |

### 5.2 Controlled correction

Any future correction workflow requires a verified database backup and recovery procedure, read-only reconciliation report, physical capacity confirmation, explicit operational approval, a separately reviewed transactional correction procedure, before/after evidence, and post-correction reconciliation.

Never infer physical capacity from current stock quantity, a frontend default, or a zero value.

## 6. Required tests

### Movement correctness

- Inbound, outbound, and transfer update stock, occupancy, and ledger consistently.
- Capacity boundary is accepted; capacity overflow is rejected.
- Occupancy cannot become negative.
- Fractional quantities and physical weights preserve approved precision.
- Invalid UOM quantities are rejected before mutation.
- Idempotent retries do not duplicate effects.

### Status behavior

- AVAILABLE, RESERVED, and DAMAGED quantities all contribute to physical occupancy.
- A status-only change does not change total occupancy.
- Physical removal decreases occupancy irrespective of the prior physical status, subject to authorization and workflow rules.

### Atomicity and concurrency

- Force a failure after one or more writes and prove all writes roll back.
- Concurrent same-bin movements preserve capacity.
- Concurrent same-item outbound operations preserve available stock.
- Concurrent transfers preserve both bins and both ledger entries.
- Stable lock ordering is exercised against MySQL.

### Reconciliation

- Aggregates multiple SKUs and all physical statuses.
- Detects occupancy mismatch and capacity overflow.
- Reports unknown capacity without inventing a value.
- Read-only execution causes no database mutation.

## 7. Current audit evidence and limitations

| Finding | Classification |
|---|---|
| Existing bin A-01-01 has stored occupancy 0.00 KG while its stock implies 30.00 KG | VERIFIED — supplied database evidence |
| Existing bin MP-A1-01 has stored occupancy 0.00 KG while its stock implies 250.00 KG | VERIFIED — supplied database evidence |
| MP-A1-01 has a recorded capacity of 500.00 KG | VERIFIED — supplied database evidence; physical provenance not established |
| A-01-01 physical capacity is unknown | UNVERIFIED — requires physical/operational confirmation |
| Inbound explicitly rounds projected occupancy to scale 2 | VERIFIED — inspected source |
| Movement DTOs use positive-value validation without UOM-specific scale enforcement | VERIFIED — inspected source |
| Inbound unit tests do not cover the same normal/capacity cases covered by outbound and transfer tests | VERIFIED — inspected test files |
| Pessimistic item/bin locks and deterministic transfer bin ordering exist in source | VERIFIED — source inspection |
| Concurrent same-SKU outbound and duplicate idempotency-key protection have MySQL Testcontainers test cases | VERIFIED — test source exists; execution result not rerun in this audit |
| Inbound/transfer concurrency and forced rollback guarantees hold under MySQL | UNVERIFIED — no corresponding integration evidence confirmed |
| No additional stock mutation or status-transition endpoint/service exists in the inspected backend source tree | VERIFIED — source-tree and controller/service inspection |
| Direct database edits or external processes cannot mutate stock/master data | UNVERIFIED — outside repository API inspection |

The two occupancy discrepancies are not authorization to update the database. No correction has been performed.

## 7.1 Repository-wide mutation-path review (2026-11-09)

The backend `main` source tree and its controllers/services were reviewed for inventory and master-data mutation paths.

| Area | Observed API/service behavior | Audit conclusion |
|---|---|---|
| Inbound | `POST /api/v1.0/inbound` delegates to `InboundService` | Stock-in mutation path |
| Outbound | `POST /api/v1.0/outbound` delegates to `OutboundService` | Stock-out mutation path |
| Transfer | `POST /api/v1.0/transfer` delegates to `TransferService` | Inter-bin movement path |
| Inventory | GET stocks, low-stock alerts, and ledger | Read-only; no status update or adjustment endpoint |
| Item | Create and list only | No item update/delete endpoint; no API to change Base UOM or weightPerUnit |
| Bin | Create and list by warehouse only | No API to change capacity/occupancy or delete a bin |
| Stock status | Status is a String field on InventoryStockEntity | No status-transition service/controller found in inspected source tree |
| Other adjustment service | No separate adjustment service/controller found | No fourth application-level stock movement path found |

The service interfaces for Item and Bin expose only create/read methods. The Inventory controller exposes only GET routes. The three movement controllers expose the three POST movement operations above.

This conclusion is scoped to the inspected backend repository source tree. It does not rule out direct SQL changes, database administration, external integrations not present in this repository, or future code added outside the reviewed branch.

### Master-data immutability risk

Although there is currently no Item update endpoint, ItemEntity exposes setters and its Base UOM and weightPerUnit are persisted master data. A direct database edit or future update path could change the calculated physical weight of historical stock without automatically recalculating stored bin occupancy.

Likewise, bin capacity has no update endpoint in the current service interface. Existing capacity values still require operational provenance and must not be assumed correct solely because they are persisted.

Any future item master-data update workflow must prevent Base UOM or weightPerUnit changes while stock or ledger history exists, or provide a separately approved historical conversion/reconciliation process. Any capacity update must validate the resulting capacity against independently calculated physical occupancy.

### Existing MySQL concurrency test coverage

`MySqlInventoryLockingIntegrationTest` uses a disposable MySQL 8.4 Testcontainers database and contains two outbound concurrency scenarios:

1. Two concurrent outbound requests each attempt to remove 7 units from an initial balance of 10; exactly one is expected to succeed, leaving stock and bin occupancy at 3 and one ledger row.
2. Two concurrent requests using the same idempotency key; only one movement is expected to apply.

These tests provide meaningful intended evidence for same-SKU outbound serialization and duplicate-request handling. Their current execution result was not rerun as part of this source inspection.

The inspected integration test does not cover concurrent inbound capacity contention, concurrent transfer contention, different-SKU movements targeting one bin, or forced database failure/rollback after partial writes. Those remain explicit test gaps.

## 7.2 Item weight and Base UOM historical integrity

The inspected ledger stores quantity changes and balances and references the current ItemEntity. It does not store a transaction-time snapshot of `weightPerUnit`, Base UOM, or calculated physical weight.

Consequently, changing an item's weight or Base UOM after stock movements can change the result of a historical occupancy reconstruction even though no physical movement occurred. The current API exposes item creation and listing only; no item update endpoint was found in the inspected backend source tree. This reduces exposure through the current application API, but does not protect against direct database edits, future update endpoints, or other write paths.

### Initial-release policy

1. Treat `baseUom` and `weightPerUnit` as immutable once an item has any inventory stock or ledger history.
2. Enforce the rule in the backend domain/service layer for any future update path; UI restrictions alone are insufficient.
3. Do not silently overwrite the historical weight definition.
4. A change to item name or description may be handled separately because it does not alter physical weight calculations.
5. If the physical weight definition must change after movements exist, require a separately approved migration/reclassification process with backup, movement/stock reconciliation, capacity validation, and audit evidence.
6. For the initial release, prefer creating a new SKU when the physical weight definition materially changes, rather than rewriting the meaning of historical transactions.

### Future alternative: effective-dated weight definitions

If the business later requires one SKU to have multiple valid weight definitions over time, the data model must preserve the applicable weight version for each stock balance and movement. A future design could introduce effective-dated item measurement versions and reference the applicable version from stock and ledger records, or store immutable transaction-time weight snapshots.

That is a larger domain/schema change and is not part of the current implementation baseline. Adding only a weight snapshot to new ledger rows would not by itself solve the historical stock problem for existing aggregate stock balances.

### Evidence classification

| Finding | Classification |
|---|---|
| Ledger entity stores quantity/balance fields and references ItemEntity; no weight/UOM snapshot field exists | VERIFIED — source inspection |
| ItemEntity holds the current Base UOM and weightPerUnit | VERIFIED — source inspection |
| Current Item service/controller has create and list operations only | VERIFIED — source inspection |
| Changing master weight can alter historical recalculated physical weight | INFERRED — consequence of current data model |
| Direct SQL or an unreviewed future write path could change master weight | RISK — not observed as an application endpoint |

## 7.3 Master-data soft-delete and lifecycle review

The inspected Item, Bin, and Warehouse entities each contain an `isDeleted` flag defaulting to false. Their current controllers expose create and read operations only; no application-level delete/deactivate endpoint was found in the inspected controller/service tree.

### Current source findings

| Area | Observed behavior | Assessment |
|---|---|---|
| Item list | `findAllByIsDeletedFalse` | Active items only |
| Bin list by warehouse | `findByWarehousePublicIdAndIsDeletedFalse` | Active bins only; does not itself establish that the parent warehouse is active |
| Warehouse list | `findAllByIsDeletedFalse` | Active warehouses only |
| Item movement lock | `findBySkuForUpdate` filters by SKU only | Does not exclude soft-deleted items |
| Bin movement lock | `findByBinCodeForUpdate` filters by bin code only | Does not exclude soft-deleted bins |
| Bin creation | Resolves warehouse by public ID without an active-state predicate | A soft-deleted warehouse is not explicitly rejected by this service |
| Current stock list | Uses `stockRepository.findAll(pageable)` | Does not filter inactive item/bin/warehouse relationships |
| Low-stock alerts | Query filters deleted item, bin, and warehouse | Active operational alert view |
| Ledger history | Reads ledger records and resolves current master references | Retaining access to historical master context is useful |
| Warehouse-to-bin relation | `cascade = CascadeType.ALL` | Future parent delete operations require explicit cascade/lifecycle review |

The movement lock queries' missing active-state predicates are a latent guardrail gap: although no current application endpoint deactivates master records, a soft-deleted record created through a direct database operation or a future workflow can still be resolved by the movement services if its SKU/bin code is supplied.

The stock listing currently includes every stock row, including rows associated with soft-deleted master records. This can be useful for audit/reconciliation but should not silently be presented as an active operational inventory view.

The unique SKU, bin-code, and warehouse-code constraints also remain in force regardless of `isDeleted`. The current create services check for existing identifiers without an active-state filter. Therefore, a soft-deleted identifier cannot simply be reused; any reuse policy would require an explicit business decision and a safe uniqueness strategy.

### Recommended lifecycle contract

1. Treat soft delete as deactivation, not physical deletion. Preserve item, bin, warehouse, stock, and ledger relationships needed to interpret historical transactions.
2. Do not deactivate an item while any non-zero stock exists in any status, or while unresolved operational reservations/adjustments exist. Historical ledger alone should not necessarily prevent deactivation; it must remain readable.
3. Do not deactivate a bin while any non-zero stock exists in any status or while its physical occupancy is non-zero. Require stock to be moved/reconciled and occupancy to be verified first.
4. Do not deactivate a warehouse while it contains active bins, non-zero stock, or unresolved operations. Require child bins to be safely deactivated first, after stock/occupancy reconciliation.
5. All future movement lock queries must require active item and active bin (and an active parent warehouse if warehouse deactivation is supported). Return a clear conflict/not-found response rather than moving stock against inactive master data.
6. Bin creation must reject an inactive warehouse.
7. Keep historical ledger queries available after master deactivation. Do not cascade-delete ledger or stock rows from a warehouse/bin/item lifecycle operation.
8. Separate operational stock views from audit/reconciliation views: active operational views should explicitly filter active master records; audit views should retain historical rows and identify inactive master records.
9. Do not allow identifier reuse by default. If SKU/bin/warehouse code reuse is required, define a new immutable identity/versioning policy first; do not weaken unique constraints casually.
10. Avoid broad `CascadeType.ALL` on the Warehouse-to-Bin relationship for lifecycle deletion. Prefer explicit service orchestration and restrictive database foreign keys; any future delete operation must not physically cascade through inventory history.

### Evidence classification

| Finding | Classification |
|---|---|
| Item, Bin, and Warehouse entities have `isDeleted` flags | VERIFIED — source |
| Current controllers/services expose create/list operations, no delete/deactivate endpoint found | VERIFIED — inspected source tree |
| Item/bin movement lock queries omit `isDeleted=false` | VERIFIED — repository source |
| Bin creation does not explicitly reject a deleted warehouse | VERIFIED — service/repository source |
| Current stock listing does not filter deleted master relationships | VERIFIED — service source |
| Low-stock alert query excludes deleted item/bin/warehouse | VERIFIED — repository source |
| Warehouse-to-bin mapping uses `CascadeType.ALL` | VERIFIED — entity source |
| Existing deployed data contains soft-deleted records with stock | UNVERIFIED — live database not inspected for this condition |
| Current UI labels or actions fully communicate lifecycle state | UNVERIFIED — frontend lifecycle review not yet completed |

This section is a domain and audit recommendation only. No endpoint, entity, repository query, migration, or database record has been changed.
## 7.4 Stock identity, uniqueness, and bin-code scope

### Current source/schema findings

1. The V1 Flyway migration defines a unique constraint on `inventory_stocks(item_id, bin_id, status)` (`uq_item_bin_status`). This expresses one aggregate stock balance per item, bin, and status.
2. `InventoryStockEntity` does not declare the equivalent JPA `@Table(uniqueConstraints=...)` metadata. The migration is therefore the only inspected declaration of this uniqueness rule.
3. The stock repository lookup uses item SKU, bin code, and status and returns `Optional`. If duplicate rows exist, the query can fail with a non-unique-result exception rather than choosing a deterministic balance.
4. All three movement services serialize by locking the item row before stock lookup. This is a useful application-level protection for same-SKU operations, but it does not replace a database uniqueness constraint against direct SQL or future write paths.
5. The current MySQL concurrency integration test sets Hibernate `ddl-auto=create` and does not run Flyway V1. Since the entity does not declare the stock unique constraint, that test schema does not establish that `uq_item_bin_status` is present. The test verifies same-SKU outbound locking/idempotency behavior, not enforcement of the V1 stock unique key.
6. V1 defines `uk_bins_bin_code` as a unique constraint on `bins.bin_code`, making bin codes globally unique across all warehouses. The Bin entity also marks `binCode` unique.
7. The current create service checks bin code globally via `findByBinCode`, consistent with the global unique constraint.
8. Ledger `request_id` is unique in V1 and in the entity mapping. Transfer creates two distinct IDs by appending `_OUT` and `_IN`; the transfer service checks both before applying a transfer.

### Assessment and design questions

- **Stock identity:** retain `(item_id, bin_id, status)` as the unique stock identity for the current aggregate model. Add matching JPA metadata or ensure every schema/test path applies the Flyway constraint. The database must remain the final guard.
- **Existing duplicates:** before adding or enforcing the constraint on an existing installation, run a duplicate-group query and reconcile duplicates; do not blindly add the constraint to production data.
- **Concurrency test schema:** prefer running Flyway against disposable MySQL in integration tests (with Hibernate `validate`) so tests exercise the same indexes and constraints as the intended application schema. If a test deliberately uses Hibernate-created schema, it must declare equivalent constraints and clearly state the limitation.
- **Bin-code scope:** global uniqueness is currently implemented and verified in V1/entity/service. Whether this is the correct business rule is **UNDECIDED**. If warehouse-local uniqueness is desired, the schema and service would need a composite unique key on `(warehouse_id, bin_code)`, and every lookup/lock/API path must include warehouse identity. Do not change this without an explicit business decision.
- **Ledger idempotency:** preserve uniqueness of request IDs. For transfer, ensure generated suffixes cannot collide with another operation's accepted request-ID namespace; a structured operation key or dedicated transfer identifier may be preferable if request IDs are not namespaced by operation.

### Evidence classification

| Finding | Classification |
|---|---|
| V1 declares unique `(item_id, bin_id, status)` | VERIFIED — migration source |
| Stock entity does not declare matching JPA unique metadata | VERIFIED — entity source |
| Stock repository lookup returns Optional for item/bin/status | VERIFIED — repository source |
| Current concurrency integration test uses Hibernate `ddl-auto=create`, not Flyway | VERIFIED — test source |
| Test therefore does not prove V1 stock unique constraint enforcement | VERIFIED — test configuration + schema declarations |
| V1 and entity make bin code globally unique | VERIFIED — migration/entity source |
| Global uniqueness is the correct business rule | UNDECIDED — requires business confirmation |
| Existing live DB contains duplicate stock identities | UNVERIFIED — live DB constraint/data not rechecked in this step |

This section records an audit/design gap only. No entity, migration, test, or database was changed.
## 8. Implementation sequence

1. Complete repository-wide review of every stock quantity and status mutation path.
2. Finalize the UOM and quantity precision contract.
3. Define one shared physical-weight calculation and precision policy.
4. Align inbound, outbound, and transfer.
5. Design read-only occupancy reconciliation.
6. Add MySQL integration, rollback, and concurrency tests.
7. Review entity mappings and design a new Flyway migration where required.
8. Separately plan existing-database adoption, backup, and data validation.
9. Update frontend presentation and UOM-aware inputs after backend contract is stable.
10. Obtain explicit approval before production implementation or data correction.

## 9. Change control

This specification is documentation only. It does not authorize production source-code modifications, database schema migrations, occupancy or capacity updates, or production deployment.

Implementation and data correction require separate explicit approval.

## 7.5 Ledger ordering, transfer traceability, and balance-chain review

### Current source findings

1. `InventoryLedgerEntity` stores item, bin, transaction type, quantity change, balance before/after, unique request ID, reference number, user ID, note, and inherited created timestamp.
2. The ledger history repository method is `findAllByOrderByCreatedAtDesc(Pageable)`. It sorts by timestamp descending but has no secondary tie-breaker such as the ledger primary key. Rows sharing the same timestamp therefore have no explicitly deterministic order in the query contract.
3. The V1 migration defines `idx_ledger_history(item_id, bin_id, created_at)`, which supports item/bin/time history access but does not itself guarantee deterministic ordering for timestamp ties.
4. `LedgerResponse` exposes public ID, item/bin details, transaction type, quantity/balance fields, reference number, user, note, and created timestamp. It does not expose the stored `requestId` or the numeric ledger ID.
5. Transfer writes two ledger rows in one `@Transactional` service method: `TRANSFER_OUT` with request ID suffix `_OUT`, and `TRANSFER_IN` with suffix `_IN`. Both rows use the SKU as `referenceNo`; the notes contain the opposite bin code.
6. The transfer unit test verifies that both save calls are made with the expected suffixes and transaction types. This is Mockito-level evidence only; it does not prove that a real MySQL transaction rolls back both rows and stock/bin writes when a failure occurs between the saves.
7. The inspected MySQL Testcontainers integration test exercises outbound concurrency only. It contains no transfer transaction/rollback scenario.
8. The six ledger rows supplied during the live-database review each satisfy `balanceBefore + quantityChange = balanceAfter`. Their reconstructed final balances match the two supplied current stock balances: SKU-NASI-001 = 30.00 and SKU-MU-001 = 250.00. Those six rows contain inbound/outbound movements; they do not provide a live transfer pair to verify.

### Assessment

- **Timestamp ordering:** the ledger endpoint's descending timestamp order is suitable for a broad recent-activity view, but not a strict chronological balance-chain guarantee when timestamps tie. A deterministic secondary order should be specified (for example, `createdAt DESC, id DESC` for display). For balance reconstruction, use a clearly defined per-item/per-bin ordering and do not treat timestamps alone as a globally reliable sequence.
- **Transfer correlation:** the database can infer a likely pair by removing the `_OUT`/`_IN` suffix from request IDs, but the API response omits request ID and the rows have no dedicated shared transfer/group identifier. SKU in `referenceNo` identifies the item, not the transfer operation. Notes are descriptive text and should not be treated as a durable machine-readable relationship.
- **Transfer atomicity:** the single `@Transactional` method is a positive structural foundation because both stock/bin updates and both ledger saves execute in one transaction. Actual rollback behavior remains unverified until a MySQL integration test deliberately fails after an intermediate write and asserts that source stock, destination stock, both bin occupancies, and both ledger rows are unchanged.
- **Balance-chain reconstruction:** the six supplied rows reconcile arithmetically and to the supplied final balances. This is limited to those rows and the two supplied item/bin balances; it does not prove completeness of all historical movements, absence of deleted/missing rows, or deterministic chronology for timestamp ties.

### Recommended contract and tests

1. Add a deterministic tie-breaker to ledger history ordering.
2. Preserve request/operation correlation in the ledger API or expose a dedicated immutable transfer identifier shared by both transfer rows. Do not rely on free-text notes.
3. Keep request IDs unique and define their namespace so generated `_OUT`/`_IN` suffixes cannot collide with independently accepted request IDs.
4. Add a MySQL Testcontainers transfer test that verifies both ledger rows, source/destination balances, and both occupancies after a successful transfer.
5. Add a forced-failure test between the two ledger saves (or after one side has been written) and prove complete rollback of all transfer effects.
6. Add a read-only balance-chain audit query/test that checks every row's arithmetic and compares the last reconstructed balance with current stock for each item/bin/status identity. Include tie handling and report missing/ambiguous ordering rather than silently choosing a sequence.

### Evidence classification

| Finding | Classification |
|---|---|
| Ledger history sorts by `createdAt DESC` without a secondary order | VERIFIED — repository source |
| V1 has a history index on item, bin, and created timestamp | VERIFIED — migration source |
| Ledger response omits request ID and numeric ledger ID | VERIFIED — response DTO source |
| Transfer saves OUT and IN rows with suffix IDs in one `@Transactional` method | VERIFIED — service source |
| Transfer unit test verifies both save calls | VERIFIED — test source |
| MySQL rollback of both transfer legs and all stock/bin changes is guaranteed at runtime | UNVERIFIED — no forced-failure integration test found |
| The six supplied ledger rows reconcile arithmetically and to the two supplied current balances | VERIFIED — user-provided database query output |
| All historical ledger rows are complete and chronological | UNVERIFIED — full live ledger history and ordering were not independently inspected |

This section records source and supplied-database evidence only. No ledger, stock, bin, schema, or application source was changed.
