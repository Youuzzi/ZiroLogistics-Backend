# WMS Inventory Tracking Dimension Strategy

- **Document ID:** DB-09
- **Version:** 0.1
- **Status:** Proposed — tracking scope and extension strategy
- **Parent:** DB-01 WMS Database Architecture
- **Depends on:** DB-02 Business Domain & Capability Model; DB-03 Logical ERD; DB-05 Inventory Integrity; DB-07 UOM & Measurement
- **Product stage:** Initial release for small and medium warehouses

## 1. Purpose

This document defines the approach for identifying inventory beyond the basic item-location-status dimensions.

Tracking dimensions can improve traceability but increase complexity in receiving, balance calculation, allocation, movement, counting, and reporting. The initial product should not require every tracking feature unless the target warehouse's business process needs it.

## 2. Tracking concepts

| Dimension | Meaning | Example use |
|---|---|---|
| Lot / Batch | Groups units produced or received together | Food, cosmetics, chemicals, manufacturing batches |
| Expiry / Best-before | Date-based usability or sale limit | Perishable or regulated goods |
| Serial number | Identifies an individual physical unit | Electronics, equipment, warranty assets |
| License plate / Handling unit | Identifies a physical container or logistics unit | Carton, pallet, tote, container |
| Owner | Identifies whose inventory is held | 3PL or consigned stock |
| Quality disposition | Captures inspection/hold/release state | Incoming inspection and quarantine |

These concepts are related but not interchangeable. An item may be lot-controlled without license plates, or serial-controlled without expiry tracking.

## 3. Initial release recommendation

For the first small/medium warehouse release:
- do not make lot, serial, expiry, or license plate mandatory in the core inventory key by default;
- do not add empty nullable tracking columns to every table merely for speculative future use;
- retain a clean extension path so tracking can be introduced consistently when a real target workflow requires it;
- maintain inventory status and reason controls separately from lot/serial identity;
- do not claim traceability features are available until their complete operational workflows exist.

If the first customer handles food, pharmaceuticals, chemicals, warranty-controlled electronics, or other goods requiring traceability, the relevant tracking dimension must be promoted into P0 before schema approval.

## 4. Extension requirements

When lot tracking is introduced:
- define lot identity and uniqueness scope;
- capture supplier/manufacturer lot references as needed;
- attach lot identity to receiving and inventory movement;
- include lot in balance and reservation dimensions;
- support lot-level stock count and reconciliation;
- define merge/split and disposition behavior.

When expiry tracking is introduced:
- define whether expiry belongs to a lot, serial, or individual stock unit;
- define date-only versus timestamp semantics;
- define FEFO allocation and blocked/expired stock rules;
- preserve expiry evidence through movements and returns.

When serial tracking is introduced:
- identify each serialized unit uniquely within the agreed ownership scope;
- prevent the same serial from being active in multiple locations;
- record unit-level receipt, movement, dispatch, return, and disposition;
- define whether serial identity is globally unique or unique per item/company.

When license plate/handling-unit tracking is introduced:
- define container identity, type, and hierarchy;
- define whether a container may hold multiple items/lots;
- define container move, split, merge, repack, and emptying rules;
- keep container identity distinct from storage location identity.

## 5. Inventory dimension evolution

A tracking extension must update all affected inventory concepts together:
- balance identity;
- transaction/movement lines;
- reservations and allocations;
- receiving and putaway;
- transfers and in-transit stock;
- picking and dispatch;
- stock counts and adjustments;
- reconciliation and reporting.

A partial implementation that stores lot/serial data only on an item or receipt but does not include it in stock identity is not valid traceability.

## 6. Decisions required before SQL

| ID | Decision | Recommended starting point |
|---|---|---|
| DB9-01 | Lot/batch in P0 | Defer unless target goods require batch traceability |
| DB9-02 | Expiry in P0 | Defer unless target goods are perishable/date-controlled |
| DB9-03 | Serial in P0 | Defer unless individually serialized goods are in target scope |
| DB9-04 | License plate in P0 | Defer; use location + item quantities for initial operation |
| DB9-05 | Owner dimension | Defer unless the company stores stock owned by external customers |
| DB9-06 | Quality disposition | Use basic inventory status initially; add formal inspection workflow when required |

## 7. Readiness gate

The P0 schema is ready when the product owner confirms whether any target warehouse category requires lot, expiry, serial, license plate, or owner tracking at launch. If none is required, these features remain deferred and are not included in the initial balance key.

---

**Public documentation note:** This document describes a staged extension strategy. It does not claim that advanced tracking is implemented.