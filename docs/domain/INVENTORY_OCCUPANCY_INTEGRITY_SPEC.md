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
| All concurrency and rollback guarantees hold under MySQL | UNVERIFIED — integration evidence required |
| No other stock mutation or status-transition path exists in the entire application | UNVERIFIED — requires repository-wide call-path review |

The two occupancy discrepancies are not authorization to update the database. No correction has been performed.

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
