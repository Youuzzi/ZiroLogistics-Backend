# WMS Database Design Decision Register

- **Document ID:** DB-13
- **Version:** 0.1
- **Status:** Open — product-owner decisions required
- **Parent:** DB-01 WMS Database Architecture
- **Scope:** Decisions that must be resolved or explicitly deferred before the first new database migration

## 1. Purpose

This register consolidates unresolved decisions across DB-01 through DB-12 and related draft domain specifications.

Its purpose is to prevent design assumptions from silently becoming permanent database constraints. Recommendations are starting points for review, not accepted decisions.

Decision status:
- **OPEN:** requires product-owner confirmation.
- **PROPOSED:** recommended option, not yet accepted.
- **DEFERRED:** intentionally excluded from the first release.
- **ACCEPTED:** explicitly confirmed and recorded.

No decision in this register should be treated as ACCEPTED unless the product owner confirms it.

## 2. SQL-blocking decisions

These decisions materially affect table relationships, balance keys, constraints, or posting semantics.

| ID | Decision | Proposed baseline | SQL impact |
|---|---|---|---|
| DEC-01 | Company and user ownership | One explicit company owns multiple warehouses; users are company-scoped with explicit warehouse assignments | Company FKs, user relationships, uniqueness scopes, access joins |
| DEC-02 | Location hierarchy | One Location entity per warehouse, optional parent, controlled location type | Location table, self-FK, warehouse-scoped unique code |
| DEC-03 | Inventory status versus reservation | Status represents physical condition/operational disposition; reservation is a separate allocation | Balance key, reservation tables, availability calculation |
| DEC-04 | Receiving posting boundary | Post accepted goods to a real receiving/staging location; putaway is a subsequent location movement | Receiving confirmation, inventory transaction, location balance |
| DEC-05 | Transfer lifecycle | Immediate within-warehouse transfer; staged dispatch/receipt for cross-warehouse only if transit visibility is needed | Transfer state, in-transit representation, posting line design |
| DEC-06 | Outbound posting boundary | Picking is execution; on-hand reduces when goods leave warehouse custody | Pick/dispatch entities and stock posting timing |
| DEC-07 | Reservation in P0 | Formal reservation or atomic availability check at pick time | Reservation/allocation table and concurrency policy |
| DEC-08 | Inventory ledger model | One authoritative immutable posting history; do not maintain two independent writable histories | Inventory transaction header/line and ledger design |
| DEC-09 | P0 tracking dimensions | Defer lot, expiry, serial, license plate, and owner unless target workflows require them | Balance dimensions and movement-line identity |
| DEC-10 | UOM conversion | Base-UOM-only first release unless purchase/issue conversion is a real requirement | UOM catalogue/conversion tables and transaction normalization |
| DEC-11 | Quantity and measurement precision | Candidate scales: quantity 3, unit weight 6, physical occupancy 9; enforce per-UOM input policy | DECIMAL definitions, validation, calculations, integration tests |
| DEC-12 | Location capacity | Optional per location; weight only where needed initially | Capacity fields/policies, occupancy and concurrency validation |

## 3. Important policy decisions

| ID | Decision | Proposed baseline | SQL/application impact |
|---|---|---|---|
| POL-01 | Negative stock | Reject negative on-hand and over-reservation | Conditional updates, constraints, service validation |
| POL-02 | Partial operations | Support partial receiving, putaway, transfer, picking, and dispatch where operationally relevant | Line execution quantities and state transitions |
| POL-03 | Stock count | Basic count session plus approved variance adjustment in P0 | Count header/lines, approval and posting linkage |
| POL-04 | Reversal | Posted movement is corrected by compensating posting, never deletion | Reversal references and uniqueness rules |
| POL-05 | Item measurement changes | Block Base UOM changes after stock history; control weight changes | Item lifecycle and historical measurement basis |
| POL-06 | Warehouse access | Role/permission and warehouse assignment are separate | Role, permission, and user-warehouse relations |
| POL-07 | Time policy | Store timestamps consistently in UTC; use explicit warehouse timezone for local business dates where needed | DATETIME/TIMESTAMP conventions and business-date logic |
| POL-08 | Identifier policy | BIGINT internal keys plus opaque public IDs is the leading candidate | PK/FK types and public ID storage |
| POL-09 | Reference data | Controlled UOM/status/location-type values seeded deterministically | Flyway reference-data migrations |
| POL-10 | Audit | Stable actor identity and immutable posting evidence | Audit columns/events and actor FKs |

## 4. Cross-document consistency issues

### 4.1 RESERVED status versus reservation entity

The existing draft Inventory Occupancy Integrity Specification (PR #5) lists AVAILABLE, RESERVED, and DAMAGED as inventory statuses. The newer DB-05 proposal treats reservation as an allocation separate from physical inventory status.

These are not yet reconciled.

**Recommended direction:** represent physical/operational stock condition as status (for example AVAILABLE, DAMAGED, BLOCKED/QUARANTINED) and represent reserved quantity in a separate reservation/allocation model. This avoids making a reservation appear to be a physical condition and allows reserved stock to remain physically on-hand.

Before approval, the existing occupancy specification and any implementation proposal must be aligned to the selected model.

### 4.2 Existing V1/V2 precision versus new schema

The current V1 uses DECIMAL(38,2) for all quantity and weight fields. The draft V2 and UOM specification propose different scales for quantity, unit weight, and physical occupancy.

**Recommended direction:** carry forward the domain-specific candidate scales only after supported UOMs, input precision, physical-weight semantics, and operational maxima are approved. Do not reuse V2 automatically.

### 4.3 Existing bins versus generic Location

The existing schema models bins directly and includes zone/rack labels on the bin. DB-06 proposes a generic Location entity with optional parent hierarchy and explicit location types.

**Recommended direction:** use the generic Location model for the new database if the product owner accepts it. The old bins schema remains a legacy reference and is not copied as-is.

### 4.4 Existing inventory ledger versus common posting model

The current application has an inventory_ledger table. DB-03/DB-05 propose a common posting identity and movement lines, but the exact choice between transaction lines serving as the authoritative ledger and a separate ledger table is open.

**Recommended direction:** maintain exactly one authoritative append-only movement history. A separate ledger table is justified only if it has a clear role and cannot diverge from posting lines.

## 5. Deferrable decisions

The following can remain outside P0 if the product confirms no target workflow requires them:
- multi-company operation beyond the initial company;
- multi-tenant SaaS isolation and tenant lifecycle;
- lot/batch tracking;
- expiry/FEFO;
- serial tracking;
- license plate/handling unit;
- advanced wave/cluster picking;
- labor, yard, dock, and automation systems;
- advanced UOM conversion;
- volume/pallet capacity if weight or simple location capacity is sufficient.

Deferral must not block a clean extension path, but no speculative P0 tables should be created solely for these possibilities.

## 6. Recommended decision order

Resolve in this order because later decisions depend on earlier ones:

1. Company/user ownership and warehouse access.
2. Location hierarchy and location types.
3. Inventory status versus reservation semantics.
4. Receiving and putaway posting boundary.
5. Transfer lifecycle and in-transit behavior.
6. Outbound, reservation, picking, and dispatch boundary.
7. Tracking dimensions required by target warehouse types.
8. UOM, conversion, precision, and physical measurement policy.
9. Ledger/posting representation, reversal, and idempotency.
10. Identifier, timestamp, audit, and migration details.

## 7. SQL readiness rule

The new SQL implementation must not begin until every SQL-blocking decision is either:
- explicitly ACCEPTED; or
- explicitly DEFERRED with a documented reason and confirmation that the omission does not invalidate the P0 model.

A recommendation written in an architecture document is not the same as product-owner approval.

---

**Public documentation note:** This register records open design questions and proposed answers. It does not represent accepted product decisions or implemented database behavior.