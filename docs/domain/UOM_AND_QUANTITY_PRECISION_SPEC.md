# UOM & Quantity Precision Specification

**Project:** ZiroLogistics WMS  
**Document ID:** WMS-DOMAIN-UOM-001  
**Version:** 0.3  
**Status:** Draft PR — domain baseline accepted for design; implementation not approved  
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

The storage scale is domain-specific rather than uniform. The implementation candidate uses three fractional digits for Base-UOM quantities, six for unit weight in KG per Base UOM, and nine for physical bin weight. The nine-digit occupancy scale preserves the product of a six-digit unit weight and a three-digit quantity without premature rounding.

| Field | Current V1 scale | Proposed V2 scale | Reason |
|---|---:|---:|---|
| `inventory_stocks.quantity` | 2 | 3 | Supports KG quantity to 0.001 |
| `inventory_ledger.quantity_change` | 2 | 3 | Same Base-UOM precision as stock |
| `inventory_ledger.balance_before` | 2 | 3 | Same Base-UOM precision as stock |
| `inventory_ledger.balance_after` | 2 | 3 | Same Base-UOM precision as stock |
| `items.min_stock_level` | 2 | 3 | Same Base-UOM precision as stock |
| `items.weight_per_unit` | 2 | 6 | Supports small KG-per-unit values such as GR/ML |
| `bins.current_weight_occupancy` | 2 | 9 | Product of 6-scale unit weight and 3-scale quantity |
| `bins.max_weight_capacity` | 2 | 9 | Same physical-weight scale as occupancy |
| `bins.min_weight_threshold` | 2 | 9 | Same physical-weight scale as occupancy |

Proposed SQL types:
- Quantity/minimum-stock fields: `DECIMAL(38,3)`.
- `items.weight_per_unit`: `DECIMAL(38,6)`.
- Bin occupancy/capacity/threshold fields: `DECIMAL(38,9)`.

The total precision remains 38 digits. The operational maximum magnitude must still be validated against real warehouse limits before production rollout.

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


## 14. Repository review — follow-up findings (2026-11-09)

This section records a second source review of the backend and frontend repositories. These are source-level findings, not new runtime test results.

### 14.1 Backend request validation is active, but not UOM-aware

The inbound, outbound, and transfer controllers all annotate their request bodies with `@Valid`. Their request DTOs use `@NotNull` and `@Positive` for quantity. This confirms that Bean Validation is invoked for these endpoints; the gap is specifically that the current constraints do not enforce the item's Base UOM precision.

The item request DTO requires a nonblank `baseUom`, non-null `minStockLevel`, and positive `weightPerUnit`. It does not currently express an allowlist of UOM codes or UOM-specific scale rules. The current entity stores `baseUom` as a String.

### 14.2 Current weight precision is insufficient for some proposed UOMs

The V1 migration defines `items.weight_per_unit` as `DECIMAL(38,2)`. If the proposed contract is KG per one Base UOM, a GR-based item commonly needs a value such as `0.001 KG/GR`; an ML-based item may need a small density-derived value such as `0.0009 KG/ML`.

Those values cannot be represented exactly at scale 2. Therefore, the precision migration must include `weight_per_unit`, not only stock and ledger quantity columns. This is a material reason to resolve the weight contract before implementation.

### 14.3 Frontend transfer behavior confirmed

The current Transfer page uses `step="0.01"` and sends `parseFloat(form.quantity)`. This is another source-level confirmation that the transfer form currently assumes two decimal places and uses JavaScript Number conversion. It should be aligned with the same UOM-driven input and serialization contract as inbound and outbound.

### 14.4 Additional domain guardrails recommended

- Backend must reject unsupported UOM codes, even if the frontend dropdown does not offer them.
- `minStockLevel` must be non-negative and obey the item's Base UOM precision. Zero should remain valid to support items with no minimum-stock alert threshold.
- `weightPerUnit` must be positive and use the canonical KG-per-Base-UOM meaning once approved.
- Once an item has stock or inventory-ledger history, changing its Base UOM or `weightPerUnit` should be prohibited in the initial implementation. The ledger currently does not snapshot transaction-time weight/UOM, so changing either master value can alter historical physical-weight reconstruction. A later controlled conversion/migration or effective-dated measurement-version workflow can be designed separately.
- For the first iteration, a centralized backend policy/registry for the five supported UOM codes is preferable to a new configurable UOM database table. This keeps the implementation bounded while ensuring that the frontend is not the authority. A database-backed UOM catalog can be introduced when tenant-specific/custom units are an actual requirement.
- The proposed integer-only GR/ML policy is a simplifying first-release decision, not a universal warehouse standard. If the intended operations include fractional grams or milliliters, that decision must change before implementation.

### 14.5 Recommended decision baseline for review

The audit recommends the following baseline, subject to product-owner confirmation:

| Decision | Recommendation | Reason |
|---|---|---|
| PCS | Integer quantity | Discrete count |
| BOX | Integer quantity | Discrete package count; no automatic conversion to PCS |
| KG | Up to 3 fractional digits | Supports gram-level quantities while retaining KG as Base UOM |
| GR | Integer quantity | One unit represents one gram |
| ML | Integer quantity initially | Simplifies first release; revisit if fractional dosing/dispensing is required |
| Weight basis | KG per one Base UOM | One canonical physical-weight unit for bin capacity |
| BOX weight | Gross weight including packaging | Bin capacity represents physical storage load |
| Base UOM changes | Prohibit after first stock/ledger transaction | Prevents historical quantities from silently changing meaning |
| UOM policy storage | Centralized backend policy initially | Avoids unnecessary configuration table before custom UOM support is required |
| Decimal database scale | Candidate `DECIMAL(38,6)` for affected quantity/weight fields | Preserves proposed quantity and small per-unit weight values; must be checked against entity mappings and migration tests |

The above is a recommendation, not an approval. The product owner must explicitly confirm or amend it before implementation.



## 15. Review disposition — working domain baseline (2026-11-09)

The product owner has instructed the review to proceed using the recommended baseline in Section 14.5. For this specification, the following are treated as the accepted working assumptions for design:

- PCS and BOX use whole-number quantities.
- KG supports up to three fractional digits.
- GR and ML use whole-number quantities in the first release.
- `weightPerUnit` represents KG per one unit of the item's Base UOM.
- BOX weight is gross weight, including packaging.
- Base UOM and `weightPerUnit` cannot be changed after the item has stock or inventory-ledger history.
- The first release uses a centralized backend UOM policy, not a configurable UOM database table.
- `DECIMAL(38,6)` remains the candidate precision for affected quantity and physical-weight columns, subject to entity/migration compatibility review.

These assumptions are a design baseline for documentation and review. They do not authorize implementation or deployment.

### Remaining implementation gates

Before production implementation, the team must still:

1. Confirm operational maximum quantity and weight ranges against MySQL `DECIMAL(38,6)`, Java `BigDecimal`, and entity column mappings.
2. Confirm exact entity/table/column mappings and all affected migration columns.
3. Define the API error response contract for UOM precision violations.
4. Define how existing records and existing databases are migrated safely.
5. Verify that all current UOM values in stored data are supported; do not assume the frontend dropdown represents every historical database value.
6. Decide whether ML-based weight is mandatory for every item or whether items without a known weight can be excluded from weight-capacity calculations under an explicit business rule.

**Implementation status remains NOT APPROVED.** A documentation PR may be reviewed and merged independently; production code and database changes require a separate explicit authorization.


## 16. Database and weight behavior audit — 2026-11-09

### 16.1 Complete decimal-field inventory from current backend entities and V1

The entity scan found decimal-valued fields in ItemEntity, InventoryStockEntity, InventoryLedgerEntity, and BinEntity. The corresponding V1 migration currently defines the following decimal columns:

| Table | Column | Current V1 type | Proposed target scale |
|---|---|---|---:|
| items | min_stock_level | DECIMAL(38,2) | 6 |
| items | weight_per_unit | DECIMAL(38,2) | 6 |
| inventory_stocks | quantity | DECIMAL(38,2) | 6 |
| inventory_ledger | quantity_change | DECIMAL(38,2) | 6 |
| inventory_ledger | balance_before | DECIMAL(38,2) | 6 |
| inventory_ledger | balance_after | DECIMAL(38,2) | 6 |
| bins | current_weight_occupancy | DECIMAL(38,2) | 6 |
| bins | max_weight_capacity | DECIMAL(38,2) | 6 |
| bins | min_weight_threshold | DECIMAL(38,2) | 6 |

No other `BigDecimal` fields were found in the current entity package during this scan. This inventory is tied to the inspected main-branch source and V1 migration.

### 16.2 Entity mapping gap

The decimal fields above are declared as Java `BigDecimal`, but the entity fields do not specify explicit JPA `precision` and `scale` attributes in their `@Column` mappings. The migration currently defines the physical scale as 2.

Before changing the schema, the implementation must explicitly align:
- JPA column precision and scale.
- Flyway migration definitions.
- DTO/API serialization expectations.
- Integration-test schema validation.

The exact precision must be validated against MySQL limits and actual business maximums. `DECIMAL(38,6)` remains a candidate, not a final approved limit.

### 16.3 Weight is currently mandatory

The current `ItemRequest` requires `weightPerUnit` to be non-null and positive. `ItemServiceImpl` persists that value. The inbound, outbound, and transfer services always calculate bin occupancy using `item.getWeightPerUnit().multiply(quantity)`.

Therefore, the current application contract does not support an item with unknown or absent weight. The proposed KG-per-Base-UOM contract can be implemented consistently with this existing mandatory-weight behavior.

**Recommendation:** keep weight mandatory in the first release. Do not silently treat missing weight as zero, because that would allow stock to enter bins without consuming recorded capacity. If the business later needs unweighed items, design an explicit bin-capacity policy (for example, a separately tracked non-weight-managed category) before changing nullability or calculation behavior.

### 16.4 Weight conversion examples under the accepted contract

The canonical stored weight remains KG per one Base UOM:

| Base UOM | Example `weightPerUnit` | Quantity | Calculated physical weight |
|---|---:|---:|---:|
| PCS | 0.250000 KG/PCS | 4 PCS | 1.000000 KG |
| BOX | 2.500000 KG/BOX (gross) | 2 BOX | 5.000000 KG |
| KG | 1.000000 KG/KG | 1.250 KG | 1.250000 KG |
| GR | 0.001000 KG/GR | 500 GR | 0.500000 KG |
| ML | 0.000900 KG/ML (example density only) | 500 ML | 0.450000 KG |

The ML example is illustrative only. The actual weight-per-ML value must come from the product's actual density or a measured product-specific value. No universal 1:1 ML-to-GR assumption is permitted.

### 16.5 Existing database compatibility boundary

V1 explicitly states that it is intended for a fresh database and does not adopt or modify an existing database. Existing installations require a separately reviewed Flyway baseline/adoption procedure.

The repository audit cannot inspect the user's live MySQL data or deployed database schema. Consequently:
- Existing UOM values and decimal data are **UNVERIFIED** until inspected in the target database.
- The presence of V1 in the repository does not prove every local or deployed database was created from V1.
- Do not run a precision migration against an existing database until Flyway history, actual column definitions, and representative data have been inspected.
- Do not assume altering DECIMAL scale is risk-free for existing values or downstream consumers.

### 16.6 Evidence classification

| Statement | Classification |
|---|---|
| Nine decimal columns above are DECIMAL(38,2) in repository V1 | VERIFIED — source/migration |
| Corresponding entity fields use BigDecimal without explicit precision/scale | VERIFIED — source |
| Item weight is mandatory and positive in ItemRequest | VERIFIED — source |
| All three movement services use weightPerUnit × quantity | VERIFIED — source |
| Current runtime database has exactly the V1 schema | UNVERIFIED |
| Existing stored UOM values conform to proposed policy | UNVERIFIED |
| DECIMAL(38,6) is sufficient for all future operational maxima | PROPOSED — requires range confirmation |



## 17. Item weight immutability and historical ledger integrity — 2026-11-09

The current `InventoryLedgerEntity` stores transaction quantity and balance values and references the current `ItemEntity`. It does not preserve a transaction-time snapshot of `baseUom`, `weightPerUnit`, or calculated physical weight.

If `weightPerUnit` is changed after movements have occurred, recalculating historical physical weight from the current item master can produce a different result even though no physical movement occurred. The current Item API has create/list operations only, but this is not a protection against direct database writes or a future update workflow.

**Initial-release rule:** treat both `baseUom` and `weightPerUnit` as immutable once the item has any stock or inventory-ledger history. Enforce this rule in the backend domain/service layer for any future item-update path; frontend restrictions alone are insufficient.

If the business later requires a weight-definition change for an existing SKU, do not overwrite the master value in place. Design a separately approved reclassification/migration process with backup, stock and occupancy reconciliation, capacity validation, and audit evidence. If multiple weight definitions must coexist over time for one SKU, introduce effective-dated measurement versions and associate stock balances and ledger movements with the applicable version, or preserve immutable transaction-time weight snapshots. A ledger snapshot alone is insufficient to resolve the weight basis of an aggregated current stock balance.

This is a design guardrail only. No item update API, database migration, or deployed data has been changed.


## 16. Precision decision refinement — implementation candidate (2026-11-09)

The first implementation candidate in Section 5 has been refined after reviewing the actual V1 migration, JPA mappings, and movement calculation path.

- Quantity fields use scale 3 because KG is the only supported fractional Base UOM in the initial policy and it allows increments of 0.001 KG.
- `weight_per_unit` uses scale 6 so small physical-weight values per GR/ML Base UOM can be represented.
- Bin occupancy and capacity fields use scale 9 because multiplying a scale-6 unit weight by a scale-3 quantity can produce a scale-9 physical-weight result.
- Inbound must not round projected occupancy to scale 2 before persistence. Outbound and transfer must retain the same precision contract.
- Flyway V2 and JPA mappings are being tested against MySQL 8.4. The movement integration tests exercise fractional inbound, outbound, and transfer values.

This is the proposed implementation contract represented by the current Draft PRs. CI passing is evidence that the tested source/migration path works; it is not production approval.

### Remaining safeguards

1. Confirm operational maximum quantity, unit weight, and bin capacity ranges; DECIMAL(38,3/6/9) retain 38 total digits but reduce integer-digit capacity as scale increases.
2. Confirm the V2 migration is applied only to a database already at Flyway V1, or a fresh database created by V1. Existing databases without Flyway history require a separate adoption/baseline and backup plan.
3. Verify the production database's current schema and data before any migration. Do not infer production schema from the repository's V1 migration or the local development database.
4. Keep quantity validation authoritative in the backend. Database scale is storage capability, not permission for every UOM to use every scale.
5. Review the Draft implementation PRs before merge; no merge or production deployment is implied by this specification update.
