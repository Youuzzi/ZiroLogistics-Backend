# WMS Warehouse Structure & Location Model

- **Document ID:** DB-06
- **Version:** 0.1
- **Status:** Proposed — warehouse/location model for review
- **Parent:** DB-01 WMS Database Architecture
- **Depends on:** DB-02 Business Domain & Capability Model; DB-03 Logical ERD
- **Product stage:** Initial release for small and medium warehouses

## 1. Purpose

This document defines how the WMS should represent companies, warehouses, and addressable operational locations without forcing small warehouses to adopt an unnecessarily complex physical hierarchy.

The initial product supports one company operating multiple warehouses. Every stock location must have a clear warehouse owner and stable identity.

## 2. Modeling goals

- Support one or multiple warehouses under one company.
- Make every inventory location addressable and unique within its warehouse.
- Support simple layouts with a flat list of locations.
- Allow optional grouping/hierarchy as warehouses become more complex.
- Distinguish storage locations from receiving, staging, picking, packing, and dispatch areas.
- Prevent stock from being silently moved when location master data changes.
- Preserve historical references after a location is deactivated.
- Keep physical capacity and handling constraints explicit and unit-aware.

## 3. Proposed ownership hierarchy

Company → Warehouse → Location

A warehouse belongs to one company. A location belongs to one warehouse. Inventory and operational documents resolve their ownership through these relationships.

In the first release, there is no need to add a full Site/Campus/Building hierarchy unless the target customer operates multiple buildings or facilities that need independent administration.

## 4. Proposed location model

### 4.1 Generic location entity

**Recommendation:** use one Location entity with:
- warehouse ownership;
- unique location code within the warehouse;
- display name;
- location type;
- optional parent location;
- active/inactive lifecycle;
- optional physical attributes and capacity policy.

This model can represent:
- RECEIVING;
- STAGING;
- STORAGE;
- PICKING;
- PACKING;
- DISPATCH;
- RETURNS;
- QUARANTINE or HOLD.

The initial location type catalogue should remain small and controlled. Exact values must be agreed with the receiving/outbound workflow.

### 4.2 Why not require separate aisle/rack/shelf/bin tables?

A separate table for every physical level may be useful for highly structured warehouses, but it creates mandatory setup work and more joins for smaller facilities.

A generic parent-child location model allows a simple warehouse to use codes such as A-01 or RACK-01-BIN-02 without requiring all hierarchy levels to exist as separate entity types.

If later operations require independent aisle/rack attributes, replenishment strategies, slotting, or automation, a more detailed hierarchy can be introduced through a reviewed extension.

### 4.3 Hierarchy rules

If parent-child locations are enabled:
1. A parent and child must belong to the same warehouse.
2. A location cannot be its own parent.
3. Cycles are prohibited.
4. A location with inventory or open tasks cannot be moved/deactivated without a controlled transition.
5. Only leaf locations (or explicitly approved location types) may hold inventory.
6. A parent grouping location should not also hold stock unless the model explicitly supports that behavior.
7. Parent changes must not implicitly move inventory.

Database foreign keys can enforce same-row references but cycle prevention generally requires application/service validation and tests. The selected implementation must be concurrency-safe.

## 5. Location type semantics

Candidate types:

| Type | Purpose | May hold inventory? | Typical workflow |
|---|---|---:|---|
| RECEIVING | Physical receipt/check-in | Proposed yes | Inbound |
| STAGING | Temporary holding before/after an operation | Proposed yes | Inbound/outbound/transfer |
| STORAGE | Reserve storage | Yes | Putaway/replenishment |
| PICKING | Forward pick area | Yes | Outbound |
| PACKING | Packing work area | Depends on process | Outbound |
| DISPATCH | Goods ready to leave custody | Proposed yes until dispatch | Outbound |
| RETURNS | Returned goods pending disposition | Proposed yes | Returns |
| QUARANTINE | Stock blocked pending review | Yes | Quality/exception handling |
| VIRTUAL_IN_TRANSIT | Logical state between warehouses | Not a physical bin | Cross-warehouse transfer |

These are proposals, not a final enum. In particular, PACKING may be a work area rather than an inventory location, and in-transit may be better represented as a transfer state or virtual location depending on the final posting model.

A location type should carry explicit behavior flags/policies rather than relying on string comparisons scattered through application code. Avoid making every possible workflow configurable in the first release.

## 6. Location identity and uniqueness

Proposed rules:
- location code is unique within one warehouse;
- public identifier is globally unique if exposed externally;
- display name need not be unique;
- location code should be stable after first operational use;
- renaming a display label must not change historical location identity;
- code changes after use should be controlled and audited.

The final schema should enforce unique (warehouse_id, location_code), not global location-code uniqueness, unless a confirmed business requirement says otherwise.

## 7. Capacity and physical constraints

Capacity is optional for locations that do not need capacity control. When enabled, each capacity measure must have an explicit unit and policy.

Potential measures:
- maximum weight;
- maximum volume;
- maximum pallet positions;
- maximum quantity by handling unit;
- item/category restrictions.

Do not combine weight, volume, and pallet capacity into one generic numeric column.

For the first release, weight capacity may be sufficient for selected locations if the product's target customers need it. It should not be mandatory for every location. Capacity checks must account for all physically present stock, including damaged/blocked stock, and must be protected against concurrent putaway.

The exact physical occupancy and precision contract is defined in DB-05 and DB-07.

## 8. Inventory-location relationship

Each balance row references one valid inventory-bearing location. The warehouse is derived through that location.

Operational validation must ensure:
- item, location, and document belong to the same company;
- source and destination locations are active and eligible for the operation;
- destination type permits the movement;
- location has sufficient capacity where capacity is enabled;
- stock is not stranded in a deactivated location;
- warehouse access is checked for the acting user.

If a location is deactivated, existing stock must first be moved or handled through an approved exception workflow. Deactivation must not delete balances or ledger history.

## 9. Warehouse-level rules

A warehouse should have:
- stable code and name;
- company ownership;
- active/inactive state;
- address/contact metadata as needed;
- optional timezone and operating calendar if operational date rules require them;
- audit fields.

Warehouse timezone and operating calendar may be important for document dates, cutoffs, and reporting. Do not derive warehouse business date solely from the server timezone.

A warehouse cannot be deleted while it has locations, inventory, open documents, or historical operational references.

## 10. Required tests

- duplicate location code within the same warehouse is rejected;
- same location code in different warehouses is allowed if company policy permits;
- location cannot reference a warehouse belonging to another company;
- parent location must belong to the same warehouse;
- self-parent and cycles are rejected;
- parent grouping location cannot receive stock if only leaf locations are permitted;
- inactive location cannot receive new stock or tasks;
- existing stock cannot be orphaned by location deactivation;
- capacity boundary is enforced;
- concurrent putaways cannot exceed enabled capacity;
- user without warehouse assignment cannot access or mutate the warehouse's locations;
- moving stock between locations preserves total warehouse/company on-hand.

## 11. Decisions required before SQL

| ID | Decision | Recommended starting point |
|---|---|---|
| DB6-01 | Location hierarchy | Generic Location with optional parent; no mandatory aisle/rack tables |
| DB6-02 | Location types | Small controlled catalogue with explicit inventory eligibility |
| DB6-03 | Inventory-bearing locations | Receiving, staging, storage, picking, dispatch and quarantine as needed |
| DB6-04 | Capacity | Optional per location; weight only where required initially |
| DB6-05 | In-transit | Model as transfer state unless an explicit virtual location is operationally useful |
| DB6-06 | Warehouse timezone | Store explicit timezone for local business-date operations if required |
| DB6-07 | Warehouse access | User-to-warehouse assignment separate from role/permission |

## 12. Readiness gate

The location schema is ready when:
- the location hierarchy strategy is accepted;
- location types and inventory eligibility are agreed;
- capacity measures and units are explicit;
- in-transit representation is aligned with transfer workflow;
- warehouse ownership and user access are consistent with DB-10;
- deactivation and stock migration rules are defined.

---

**Public documentation note:** This is a proposed warehouse/location model, not a statement that these capabilities are implemented in the current application.