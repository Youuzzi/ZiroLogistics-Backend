# WMS Item, UOM & Measurement Specification

- **Document ID:** DB-07
- **Version:** 0.1
- **Status:** Proposed — consolidated measurement baseline
- **Parent:** DB-01 WMS Database Architecture
- **Related reference:** UOM & Quantity Precision Specification v0.3 (Draft PR #4)
- **Product stage:** Initial release for small and medium warehouses\n> **V1 decision authority:** For the first WMS schema, DB-14 — WMS Final Database Design Baseline is authoritative. Any OPEN/PROPOSED alternatives in this reference document are superseded where DB-14 records a FROZEN V1 decision. Retain this document for rationale and detailed requirements; do not reopen a closed DB-14 decision without new evidence or an approved scope change.\n

## 1. Purpose and relationship

This document consolidates the item identity, base unit of measure, quantity precision, and physical-weight requirements that must guide the new database schema.

The detailed UOM & Quantity Precision Specification v0.3 in Draft PR #4 remains the deeper domain reference. This DB-07 document connects that work to the new database model and records which rules remain proposals pending product-owner approval.

## 2. Item identity

An Item represents a stock keeping identity controlled by the company.

Candidate P0 attributes:
- company ownership;
- SKU;
- display name;
- optional description/category;
- active/inactive lifecycle;
- base UOM;
- physical weight per one Base UOM where weight-capacity control is enabled;
- audit metadata.

SKU uniqueness should be scoped to company unless a confirmed product requirement requires global uniqueness. SKU is a business identifier, not the relational primary key.

An item must not be physically redefined after stock or posted movement history exists. Changes to Base UOM or physical-weight semantics require a controlled conversion/revaluation process or must be blocked.

## 3. Base UOM and quantity

Every item has one Base UOM. Stock balances, movement quantities, minimum stock levels, and ledger quantity effects for that item must use the same Base UOM.

The current proposed initial UOM set is:
- PCS;
- BOX;
- KG;
- GR;
- ML.

The exact supported set is subject to product-owner approval. A conversion engine between purchase, storage, and issue units is not assumed for the first implementation. If the initial product uses only Base UOM, the API and database must make that constraint explicit.

## 4. Proposed quantity precision

The existing UOM domain specification proposes:

| Base UOM | Maximum fractional digits | Example |
|---|---:|---|
| PCS | 0 | 12 |
| BOX | 0 | 5 |
| KG | 3 | 1.250 |
| GR | 0 | 500 |
| ML | 0 | 750 |

This is a conservative starting policy, not a universal rule. Fractional GR or ML may be required by real target warehouses and must be decided before implementation.

Backend validation must:
- reject null, zero, and negative movement quantities;
- reject unsupported UOMs;
- reject values exceeding the approved effective fractional scale;
- avoid silently rounding stock input;
- apply consistently to inbound, outbound, transfer, count, and adjustment;
- validate minimum stock using the same Base UOM policy.

Insignificant trailing zeroes should not cause rejection if the numeric value is exactly representable at the allowed scale.

## 5. Physical weight semantics

For capacity calculations, use kilogram (KG) as the canonical physical-weight unit.

The proposed semantic definition is:

**weightPerUnitKg = physical weight in KG of exactly one unit of the item's Base UOM.**

Formula:

totalWeightKg = quantity × weightPerUnitKg

Examples:
- KG Base UOM: 1.000000 kg per KG.
- GR Base UOM: 0.001000 kg per GR.
- PCS: product-specific physical weight per piece.
- BOX: product-specific gross weight per box, including packaging if that is the selected operational policy.
- ML: product-specific weight per milliliter based on actual density; do not assume 1 ML equals 1 GR.

The BOX gross-weight policy and ML density source require confirmation. Weight per unit must be positive when capacity calculation is enabled.

## 6. Candidate database precision

The related UOM specification proposes these field scales:

| Measurement | Candidate SQL type | Reason |
|---|---|---|
| Base quantity and minimum stock | DECIMAL(38,3) | Supports KG to 0.001 |
| Weight per Base UOM in KG | DECIMAL(38,6) | Supports small unit weights |
| Physical occupancy/capacity in KG | DECIMAL(38,9) | Product of 3-scale quantity and 6-scale unit weight |

These are candidate storage envelopes, not permission to enter arbitrary precision or operate at extreme magnitudes. Backend policy must enforce the allowed UOM scale and realistic operational maxima.

The new schema must use exact DECIMAL types, never FLOAT or DOUBLE, for stock and physical measurements. Final precision must be approved with DB-05 and tested against MySQL 8.4.

## 7. UOM conversion

For the first release, prefer Base-UOM-only operation unless the target warehouse has a confirmed need for purchasing, storage, and issue in different units.

If conversion is approved:
- define whether conversion is global or item-specific;
- define valid dimensional relationships;
- define exact conversion factor precision;
- define rounding and residual handling;
- preserve the transaction UOM and normalized Base-UOM quantity;
- do not silently apply invalid conversions (for example, volume-to-mass without item-specific density).

A generic conversion table should not be added merely because multiple UOM values exist in the item master.

## 8. Item measurement lifecycle

- Base UOM is required and controlled.
- UOM definitions referenced by posted movements must not be destructively changed.
- Item Base UOM must be immutable after inventory history exists unless a reviewed conversion/migration process is used.
- Weight per unit must not be changed in a way that silently rewrites historical physical occupancy.
- If weight changes are allowed, define whether current occupancy is recalculated and how the historical measurement basis is preserved.
- Minimum stock is expressed in the item's Base UOM.
- Barcode/packaging-level identifiers are separate from UOM conversion and may be introduced as a later capability.

## 9. Required tests

- supported UOM values are accepted;
- unsupported UOM values are rejected;
- each UOM's effective scale is enforced consistently across movement types;
- trailing insignificant zeroes are handled consistently;
- quantity overflow is rejected before persistence;
- unit weight is positive and within the approved decimal envelope;
- physical-weight calculation uses exact decimal arithmetic;
- quantity × unit weight preserves the selected occupancy scale;
- invalid UOM conversion is rejected if conversions are enabled;
- item Base UOM cannot be changed after posted stock history without approved migration;
- changes to unit weight do not silently corrupt existing occupancy.

## 10. Decisions required before SQL

| ID | Decision | Recommended starting point |
|---|---|---|
| DB7-01 | Initial UOM catalogue | PCS, BOX, KG, GR, ML only if all are genuinely needed |
| DB7-02 | Quantity scale | Use proposed per-UOM scales; confirm fractional GR/ML needs |
| DB7-03 | UOM conversion | Base-UOM-only for first release unless operationally required |
| DB7-04 | BOX weight | Use gross packaged weight for capacity if confirmed |
| DB7-05 | ML weight | Require product-specific density; no generic 1:1 assumption |
| DB7-06 | Weight requirement | Decide whether every item requires weight or only items in capacity-managed locations |
| DB7-07 | Weight changes | Block after posted stock or require controlled recalculation and historical semantics |
| DB7-08 | Operational maxima | Define realistic maximum quantity, weight, and capacity independent of DECIMAL envelope |

## 11. Readiness gate

The schema is ready when:
- supported Base UOMs are approved;
- per-UOM quantity scale is approved;
- conversion requirements are decided;
- weight-per-unit semantics are accepted;
- physical capacity dimensions are agreed;
- precision and operational maxima are approved;
- item measurement immutability rules are defined.

---

**Public documentation note:** This document describes a proposed measurement contract and references a separate draft domain specification. It does not claim the new measurement model is implemented.