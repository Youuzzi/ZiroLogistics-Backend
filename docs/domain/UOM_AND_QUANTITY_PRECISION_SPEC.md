# UOM & Quantity Precision Specification

**Project:** ZiroLogistics WMS  
**Document ID:** WMS-DOMAIN-UOM-001  
**Version:** 0.1  
**Status:** Draft — Proposed, not approved for implementation  
**Last updated:** 2026-11-09

> This document records the proposed domain contract and the findings that motivate it. It is not authorization to change production code, database migrations, or deployed data.

## 1. Purpose

Define a consistent contract for:

- Item base unit of measure (Base UOM).
- Quantity precision and validation.
- Physical item weight and bin capacity calculations.
- Stock and inventory-ledger consistency.
- API and frontend decimal handling.
- Database precision and migration strategy.

The backend is the source of truth. Frontend validation is a usability aid and must never replace backend validation.

## 2. Current-state audit findings

The following findings are based on repository source inspection. They describe the inspected source, not every possible deployed database or runtime configuration.

| Area | Observed state | Assessment |
|---|---|---|
| Item Base UOM | `ItemEntity.baseUom` is a String; no UOM precision metadata is present | UOM-specific quantity precision is not currently modeled |
| Item UOM input | Frontend offers PCS, BOX, KG, GR, and ML | UI options do not enforce equivalent backend rules |
| Quantity validation | Movement request validation uses positivity checks | Positive values with unsupported fractional scale can pass validation |
| Stock and ledger | V1 schema uses `DECIMAL(38,2)` for stock quantity and ledger quantity/balances | Values with more than two fractional digits cannot be represented exactly |
| Minimum stock | `minStockLevel` is compared with summed stock quantity | Both values must use the item's same Base UOM and precision policy |
| Item weight | `weightPerUnit` is a BigDecimal; UI labels it KG | The intended denominator/base-unit contract needs to be explicit |
| Inbound bin occupancy | Inbound calculation applies `setScale(2, HALF_UP)` to projected occupancy | Precision can be discarded during the calculation |
| Outbound and transfer occupancy | Calculations do not apply the same explicit two-decimal rounding at the service calculation point | Rounding behavior is not expressed consistently across movement types |
| Frontend quantity | Inbound/outbound use `parseFloat()`; outbound has `step=0.01`; inbound does not specify a step | Input precision behavior is inconsistent and JS Number is used |
| Frontend preview | Inbound weight preview uses JavaScript arithmetic and `.toFixed(2)` | Preview is display-only and must not define transaction values |

### 2.1 Runtime evidence already collected

The audit-only integration test `MySqlInventoryQuantityPrecisionIntegrationTest` ran against MySQL 8.4 with Flyway V1 applied and Hibernate schema validation enabled.

It demonstrated that a quantity of `0.004` passes the current positive-value validation and is effectively persisted/recorded as `0.00` under the V1 two-decimal schema for inbound, outbound, and transfer scenarios.

Test result recorded by the user: 3 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.

This verifies the behavior against a fresh database created from repository migration V1. It does not prove that every existing or deployed database has exactly the same schema.

## 3. Domain model: separate quantity from physical weight

Quantity and physical weight are different measurements and must have separate precision policies.

### 3.1 Quantity

Quantity is measured in the item's Base UOM.

Examples:

- `12 PCS` means twelve pieces.
- `5 BOX` means five boxes.
- `1.250 KG` means 1.250 kilograms.
- `500 GR` means 500 grams.
- `750 ML` means 750 milliliters.

Stock quantity, movement quantity, ledger quantity change, ledger balances, and minimum stock level must all refer to the same Base UOM for a given item.

### 3.2 Physical weight

For bin-capacity calculations, physical weight should use a single canonical unit: kilogram (KG).

**Proposed contract:**

`weightPerUnitKg` means the physical weight in KG of exactly one unit of the item's Base UOM.

Formula:

`totalWeightKg = weightPerUnitKg × quantity`

Examples:

- Base UOM PCS: 0.250 KG per PCS.
- Base UOM BOX: 2.500 KG per BOX.
- Base UOM GR: 0.001 KG per GR, if the item is represented by individual grams.
- Base UOM ML: weight per ML must be based on the product's actual density; do not assume 1 ML = 1 GR.

For BOX, the recommended operational definition is gross weight including packaging, because the bin holds the packaged physical item. This remains a domain decision to confirm.

The existing field name `weightPerUnit` may be retained for compatibility or renamed in a separately reviewed change. The semantic contract must be explicit either way.

## 4. Proposed initial UOM precision policy

The following is a proposal for review, not an approved business rule.

| Base UOM | Proposed maximum fractional digits | Example |
|---|---:|---|
| PCS | 0 | 12 |
| BOX | 0 | 5 |
| KG | 3 | 1.250 |
| GR | 0 | 500 |
| ML | 0 | 750 |

Rationale:

- PCS and BOX are normally counted as whole units.
- Three decimal places for KG allow quantities down to one gram.
- Integer GR and ML are a conservative first operational policy, not a universal requirement.
- If real workflows require fractional GR or ML, the policy should be revised before implementation.

The policy should be centralized in one backend UOM definition/policy component. Do not scatter UOM-specific conditions throughout controllers and services.

A future configurable UOM catalog may store allowed fractional digits and other metadata. A conversion engine is explicitly outside the first implementation scope.

## 5. Proposed database precision

A candidate storage scale is six fractional digits for quantity and weight-related decimal values, subject to validation against actual domain ranges and MySQL/JPA mappings.

| Field | Current V1 scale | Candidate scale |
|---|---:|---:|
| `inventory_stocks.quantity` | 2 | 6 |
| `inventory_ledger.quantity_change` | 2 | 6 |
| `inventory_ledger.balance_before` | 2 | 6 |
| `inventory_ledger.balance_after` | 2 | 6 |
| `items.min_stock_level` | 2 | 6 |
| `items.weight_per_unit` | 2 | 6 |
| `bins.current_weight_occupancy` | 2 | 6 |
| `bins.max_weight_capacity` | 2 | 6 |
| `bins.min_weight_threshold` | 2 | 6 |

Candidate SQL type: `DECIMAL(38,6)`.

Important constraints:

1. A wider database scale does not authorize every UOM to use six fractional digits.
2. Backend validation remains responsible for enforcing the item's UOM policy.
3. Stock and ledger fields must use compatible precision and scale.
4. Physical-weight fields must use a consistent weight scale and unit.
5. Do not round quantity merely to fit the database.
6. Any schema change must be delivered through a new Flyway migration. Do not rewrite V1 after it has been applied.
7. Existing database adoption and data migration require a separately reviewed plan; a fresh-install migration test alone is not sufficient.

## 6. Backend validation contract

Validation must happen after the backend resolves and locks/loads the item as required by the transaction workflow, but before any stock, ledger, or bin state is mutated.

For a quantity with maximum scale N:

- Reject null, zero, and negative quantities.
- Reject quantities whose effective fractional scale exceeds N.
- Do not silently round invalid input.
- Return a clear client error that identifies the invalid quantity/UOM rule.
- Apply the same policy to inbound, outbound, and transfer.
- Apply the same Base UOM rule to minimum-stock values when creating/updating an item.

Example using the proposed policy:

| UOM | Input | Expected result |
|---|---:|---|
| PCS | 10 | Accept |
| PCS | 10.5 | Reject |
| BOX | 2 | Accept |
| BOX | 2.25 | Reject |
| KG | 1.250 | Accept |
| KG | 1.2505 | Reject |
| GR | 500 | Accept |
| GR | 500.5 | Reject |
| ML | 750 | Accept |
| ML | 750.5 | Reject |

The policy must define whether trailing zeroes beyond the maximum scale are accepted. Recommended behavior: validate the numeric value's representable scale, so `1.2500` is numerically equivalent to `1.25`; do not reject solely because the input text contains insignificant trailing zeroes.

## 7. Transaction calculation contract

### 7.1 Inbound

- Validate quantity against the item's Base UOM.
- Increase stock by the exact accepted quantity.
- Write ledger quantity change and balance values consistently.
- Increase bin occupancy by `weightPerUnitKg × quantity`.
- Check projected occupancy against the bin capacity before persisting.

### 7.2 Outbound

- Validate quantity against the item's Base UOM.
- Reject quantity greater than available stock.
- Decrease stock by the exact accepted quantity.
- Write ledger quantity change and balance values consistently.
- Decrease bin occupancy using the same physical-weight formula.
- Reject negative occupancy or inconsistent stock/weight state.

### 7.3 Transfer

- Validate quantity against the item's Base UOM.
- Decrease source stock and increase destination stock by the same exact quantity.
- Write matching transfer-out and transfer-in ledger entries.
- Decrease source-bin occupancy and increase destination-bin occupancy using the same weight basis.
- Check destination capacity before committing.
- Preserve transaction atomicity and existing idempotency/locking guarantees.

### 7.4 Rounding

- Use `BigDecimal` for backend decimal calculations.
- Do not use premature rounding during multiplication or balance calculations.
- Inbound, outbound, and transfer must share one explicit weight-calculation policy.
- Any rounding boundary and rounding mode must be documented and tested.
- Display formatting must not alter the value submitted to the API.

## 8. Frontend contract

- Keep user-entered decimal values as strings until serialization.
- Avoid converting quantity to JavaScript Number using `parseFloat()` before sending it to the API.
- Configure input step/precision based on the selected item's Base UOM.
- Display the item's Base UOM beside quantity inputs and stock balances.
- Display physical weight explicitly in KG.
- Treat frontend previews as informational only; backend calculations are authoritative.
- Do not implement a separate frontend precision policy that can drift from backend rules.

## 9. Required test coverage

### Quantity validation

- Each supported UOM accepts valid quantities.
- Each UOM rejects values exceeding its configured scale.
- Null, zero, and negative values are rejected.
- Insignificant trailing zeroes follow the documented rule.
- Invalid quantities are rejected before stock, ledger, or bin mutations.

### Stock and ledger integrity

- Inbound stock delta equals ledger quantity change.
- Outbound stock delta equals ledger quantity change.
- Transfer source decrement equals destination increment.
- Ledger balance-before and balance-after values reconcile.
- Small valid quantities do not become zero through database scale truncation.

### Bin occupancy

- Inbound, outbound, and transfer use the same weight formula.
- Fractional weight calculations retain the chosen precision.
- Capacity cannot be exceeded.
- Occupancy cannot become negative.
- Transfer source and destination occupancy changes reconcile.

### Regression

- Request-id idempotency remains correct.
- Pessimistic locking continues to prevent overselling and inconsistent occupancy.
- Flyway migration applies successfully to MySQL 8.4.
- Hibernate `ddl-auto=validate` passes against the migrated schema.
- Existing audit and inventory integration tests remain green.

## 10. Explicitly out of scope for the first iteration

- Automatic BOX-to-PCS conversion.
- General UOM conversion engine.
- Multiple inventory units for one SKU.
- Product density catalog and automatic ML-to-KG conversion.
- Changing an item's Base UOM after inventory transactions exist.
- Reworking unrelated warehouse or logistics features.

These capabilities require separate domain design and approval.

## 11. Decisions requiring product/domain approval

Before implementation, confirm:

1. Are PCS and BOX always whole-number quantities?
2. Is three-decimal precision sufficient for KG?
3. Are integer-only GR and ML sufficient for the first iteration?
4. Does `weightPerUnit` formally mean KG per one Base UOM?
5. Does BOX weight include packaging (gross weight)?
6. Must Base UOM become immutable once an item has inventory history?
7. Should the initial precision policy be fixed in code or stored in a UOM catalog/configuration table?
8. What is the maximum operational quantity/weight range that must be supported?

## 12. Implementation sequence after approval

1. Finalize domain decisions in this specification.
2. Implement a centralized backend UOM precision policy and validation.
3. Align inbound, outbound, and transfer quantity/weight calculations.
4. Design and review a new Flyway migration, including existing-data compatibility.
5. Add MySQL integration tests for quantity, ledger, and bin occupancy precision.
6. Update frontend decimal handling and UOM-aware input behavior.
7. Run regression tests and repeat the audit.

## 13. Change control

This document is a draft specification only.

No production source code, entity mapping, migration, frontend behavior, or deployed database has been changed as part of creating this document. Implementation requires explicit approval after the domain decisions above are resolved.
