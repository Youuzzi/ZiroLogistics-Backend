# WMS Logical Entity Relationship Specification

- **Document ID:** DB-03
- **Version:** 0.1
- **Status:** Proposed — logical model for review
- **Parent:** DB-01 WMS Database Architecture
- **Depends on:** DB-02 WMS Business Domain & Capability Model
- **Product stage:** Initial release for small and medium warehouses\n> **V1 decision authority:** For the first WMS schema, DB-14 — WMS Final Database Design Baseline is authoritative. Any OPEN/PROPOSED alternatives in this reference document are superseded where DB-14 records a FROZEN V1 decision. Retain this document for rationale and detailed requirements; do not reopen a closed DB-14 decision without new evidence or an approved scope change.\n

## 1. Purpose and status

This document proposes the logical entities, ownership paths, and relationships required to support one company operating multiple small/medium warehouses.

It is a logical model, not a physical SQL contract. Entity names, cardinalities, optionality, unique constraints, deletion rules, and exact columns must be reviewed before DB-04 is finalized.

The ERD distinguishes business documents from physical execution, inventory balance from movement history, warehouse locations from hierarchy labels, and user authorization from warehouse access assignment. It also separates current-release entities from deferred tracking extensions.

No entity listed here should be treated as implemented merely because it appears in this proposal.

## 2. Modeling rules

1. Every operational record must have an unambiguous company ownership path.
2. Every warehouse belongs to exactly one company in the initial model.
3. Every addressable warehouse location belongs to exactly one warehouse.
4. An inventory balance belongs to one item, one location, and one inventory status at minimum.
5. Every posted inventory effect must have a durable posting identity and ledger evidence.
6. Business document lines preserve requested, received, picked, and dispatched quantities separately where those stages differ.
7. A transfer identifies source and destination; it is not an unexplained quantity edit.
8. A user role and a user's warehouse scope are separate relationships.
9. Deactivation of master data must not erase historical operational references.
10. Optional P1/P2 dimensions must not be required in the P0 balance key.

## 3. Logical entity catalogue

### 3.1 Organization and access

| Entity | Responsibility | Core relationships | Initial scope |
|---|---|---|---|
| Company | Owns warehouse operations | Company 1:N Warehouse; Company 1:N User if company-scoped users are chosen | P0 |
| User | Authenticated actor | User N:M Role; User N:M Warehouse through assignment | P0 |
| Role | Groups authorized actions | Role N:M Permission; User N:M Role | P0 |
| Permission | Backend-enforced action | Role N:M Permission | P0 |
| UserWarehouseAccess | Limits user's operational warehouse scope | User N:1; Warehouse N:1 | P0 |

The exact user-to-company cardinality must follow the existing authentication model. A single-company first release can still support multiple users and explicit warehouse assignments. Do not create a multi-tenant membership framework without a real requirement.

### 3.2 Warehouse structure

| Entity | Responsibility | Core relationships | Initial scope |
|---|---|---|---|
| Warehouse | Physical/logical facility | Company 1:N Warehouse | P0 |
| LocationType | Classifies operational location behavior | LocationType 1:N Location | P0 |
| Location | Addressable place in a warehouse | Warehouse 1:N Location; optional self-parent hierarchy | P0 |
| Zone | Optional named grouping | Candidate relationship to Location | Optional |

**Proposed simplification:** use a warehouse-scoped Location entity with a location type and optional parent location. This can represent receiving, staging, storage, picking, and dispatch locations without forcing every small warehouse to maintain separate aisle/rack/shelf tables. A separate Zone entity can be introduced if a real workflow requires attributes or lifecycle independent of locations.

The parent-child hierarchy must prevent cycles and cross-warehouse parent relationships. A location's warehouse ownership should be stable and validated.

### 3.3 Item and measurement

| Entity | Responsibility | Core relationships | Initial scope |
|---|---|---|---|
| Item | Stock identity and handling policy | Company 1:N Item | P0 |
| Uom | Controlled unit of measure | Uom 1:N Item base UOM | P0 |
| ItemUomConversion | Approved item-specific conversion | Item 1:N conversion | Conditional P0 |
| ItemBarcode | Alternate machine identifier | Item 1:N barcode | P1 unless needed for launch |
| ItemCategory | Classification | Category self-parent optional; Item N:1 optional | Optional P0 |

The model must decide whether UOM conversion is required at launch or whether every item initially transacts only in its base UOM. Avoid ambiguous conversion factors without a defined dimensional and rounding policy.

### 3.4 Inventory core

| Entity | Responsibility | Core relationships | Initial scope |
|---|---|---|---|
| InventoryStatus | Controlled stock condition/availability state | Status 1:N Balance and movement lines | P0 |
| InventoryBalance | Current quantity by inventory dimensions | Item N:1; Location N:1; Status N:1 | P0 |
| InventoryTransaction | Common identity and posting metadata for a stock-changing operation | Company 1:N; source linkage to be finalized | P0 |
| InventoryTransactionLine | Posted quantity effect at a location/status | Transaction 1:N; Item, Location, Status N:1 | P0 |
| InventoryLedger | Append-only movement evidence or a ledger view/table | Relationship to transaction line | P0 |
| InventoryReservation | Commitment against eligible stock | Outbound line and inventory dimension | Conditional P0 |

**Key distinction:** InventoryBalance is a maintained current-state projection. InventoryTransaction and its lines are the durable business posting record. The exact choice between a dedicated InventoryLedger table and transaction lines serving as the ledger must be decided in DB-05. Do not create two independently writable histories.

At minimum, a posted movement line should preserve item, location, status, signed quantity change, unit, posting time, actor, and source transaction identity. Before/after balance snapshots may be stored for audit convenience, but they must be written atomically and must not replace the signed movement record.

The candidate balance key is (item_id, location_id, inventory_status_id). Lot, serial, and license plate are excluded from the P0 key unless tracking is explicitly required.

### 3.5 Inbound

| Entity | Responsibility | Core relationships | Initial scope |
|---|---|---|---|
| InboundReceipt | Records expected/actual incoming goods | Warehouse 1:N; Company 1:N | P0 |
| InboundReceiptLine | Item-level receipt detail | Receipt 1:N; Item N:1 | P0 |
| ReceivingConfirmation | Records verified receiving outcome | Receipt/line 1:N or 1:1 depending partial receiving | P0 |
| PutawayTask | Directs placement into a destination location | Receipt line 1:N; destination Location N:1 | P0 |
| PutawayConfirmation | Records actual placement | Task 1:N for partial execution | P0 |

Partial receiving and partial putaway must be supported if the business process allows them. Receipt quantities, accepted quantities, rejected/damaged quantities, and putaway quantities must not be collapsed into one mutable field.

The point at which received stock becomes on-hand remains open. If receiving/staging is a real inventory location, receiving can post stock there and putaway can post a location-to-location transfer. If the business instead posts only after putaway, that must be explicit and reconciled with physical custody.

### 3.6 Internal movement and stock control

| Entity | Responsibility | Core relationships | Initial scope |
|---|---|---|---|
| StockTransfer | Requests a location-to-location movement | Warehouse/source/destination scope | P0 |
| StockTransferLine | Item and quantity requested/moved | Transfer 1:N; Item N:1 | P0 |
| TransferConfirmation | Records dispatch/receipt or immediate move | Transfer line 1:N if partial | P0 |
| StockCount | Defines physical count session and scope | Warehouse 1:N | P0 |
| StockCountLine | Captures expected and counted quantity | Count 1:N; Item and Location N:1 | P0 |
| StockAdjustment | Controlled correction with reason and approval | Adjustment 1:N lines; posting identity | P0 |
| StockAdjustmentLine | Item/location/status quantity correction | Adjustment 1:N | P0 |

Whether transfer requires an in-transit location/state depends on whether source dispatch and destination receipt are separate events. The model must not assume an instantaneous move if the real process permits stock to be in transit.

### 3.7 Outbound

| Entity | Responsibility | Core relationships | Initial scope |
|---|---|---|---|
| OutboundOrder | Records demand to dispatch goods | Warehouse 1:N; Company 1:N | P0 |
| OutboundOrderLine | Item and requested quantity | Order 1:N; Item N:1 | P0 |
| InventoryReservation | Reserves eligible stock, if enabled | Order line 1:N; inventory dimension | Conditional P0 |
| PickTask | Work to retrieve stock | Order line 1:N; source Location N:1 | P0 |
| PickConfirmation | Records actual picked quantity | Pick task 1:N for partial picking | P0 |
| Shipment | Dispatch grouping/document | Outbound order 1:N or N:1 must be decided | P0 |
| ShipmentLine | Actual dispatched item quantity | Shipment 1:N; order line N:1 | P0 |

Requested, reserved, picked, packed, and dispatched quantities represent different stages. The schema must not use one quantity field to represent all of them.

## 4. Proposed logical relationship diagram

The diagram below is a domain-level overview. It omits join tables, audit columns, and some operational line entities for readability.

~~~~mermaid
erDiagram
    COMPANY ||--o{ WAREHOUSE : owns
    COMPANY ||--o{ ITEM : owns
    COMPANY ||--o{ USER : employs
    USER ||--o{ USER_WAREHOUSE_ACCESS : assigned
    WAREHOUSE ||--o{ USER_WAREHOUSE_ACCESS : grants
    WAREHOUSE ||--o{ LOCATION : contains
    LOCATION_TYPE ||--o{ LOCATION : classifies
    LOCATION o|--o{ LOCATION : parent_of
    UOM ||--o{ ITEM : base_unit
    ITEM ||--o{ INVENTORY_BALANCE : tracked
    LOCATION ||--o{ INVENTORY_BALANCE : holds
    INVENTORY_STATUS ||--o{ INVENTORY_BALANCE : classifies
    INVENTORY_TRANSACTION ||--|{ INVENTORY_TRANSACTION_LINE : posts
    ITEM ||--o{ INVENTORY_TRANSACTION_LINE : moved
    LOCATION ||--o{ INVENTORY_TRANSACTION_LINE : affected
    INVENTORY_STATUS ||--o{ INVENTORY_TRANSACTION_LINE : classified
    WAREHOUSE ||--o{ INBOUND_RECEIPT : receives
    INBOUND_RECEIPT ||--|{ INBOUND_RECEIPT_LINE : contains
    ITEM ||--o{ INBOUND_RECEIPT_LINE : received
    INBOUND_RECEIPT_LINE ||--o{ PUTAWAY_TASK : putaway
    LOCATION ||--o{ PUTAWAY_TASK : destination
    WAREHOUSE ||--o{ STOCK_TRANSFER : manages
    STOCK_TRANSFER ||--|{ STOCK_TRANSFER_LINE : contains
    ITEM ||--o{ STOCK_TRANSFER_LINE : transferred
    WAREHOUSE ||--o{ OUTBOUND_ORDER : fulfils
    OUTBOUND_ORDER ||--|{ OUTBOUND_ORDER_LINE : contains
    ITEM ||--o{ OUTBOUND_ORDER_LINE : requested
    OUTBOUND_ORDER_LINE ||--o{ PICK_TASK : picked
    OUTBOUND_ORDER ||--o{ SHIPMENT : dispatched
~~~~

The diagram is intentionally conceptual. Exact FK direction and optionality must be confirmed in DB-04.

## 5. Posting model — proposed common pattern

Every stock-changing workflow should use one consistent posting boundary:

1. A business document is created and validated.
2. Operational execution records the physical result.
3. A posting operation is requested with a stable idempotency identity.
4. The backend validates ownership, status, location, quantity precision, and stock/capacity rules.
5. The database transaction writes the posting identity, movement lines, and balance changes atomically.
6. The posting is committed once; retry returns the prior result or a safe duplicate response.

A common InventoryTransaction identity can unify inbound, putaway, transfer, adjustment, count variance, and outbound stock effects. Source-document linkage must use a referentially safe design. Avoid an unconstrained (source_type, source_id) polymorphic reference unless the integrity trade-off is explicitly accepted.

Possible source-link approaches to compare in DB-05:
- each source document has a nullable unique FK to the posting header;
- a common operational document supertype with typed detail tables;
- a posting header stores source type/reference for traceability while source documents independently hold a nullable FK, with database-enforced linkage where possible.

The selected pattern must avoid duplicate posting, orphaned posting, and ambiguity when a source document is partially posted.

## 6. Referential integrity and lifecycle requirements

- Company deletion must not cascade through warehouse operational history.
- Warehouse deletion must be blocked while operational references exist; deactivation is preferred.
- Location deactivation must be blocked while it contains stock or has open tasks.
- Item deactivation must not remove inventory or ledger history.
- UOM definitions used by posted transactions must be immutable or versioned.
- Posted transaction lines must not be updated/deleted through normal application flows.
- A document with posted quantities must use explicit cancellation/reversal semantics.
- Parent location must belong to the same warehouse and must not create a cycle.
- User access revocation must not rewrite historical actor references.
- Foreign keys should use restrictive delete behavior for operational history; cascade should be limited to safe dependent draft data where explicitly justified.

## 7. Deferred extension points

The initial balance model should not require these dimensions:
- Lot / batch;
- serial number;
- expiry date;
- license plate / handling unit;
- owner/customer inventory ownership;
- quality inspection disposition;
- country of origin or customs attributes.

If P1 tracking is approved, the inventory identity model must be extended consistently across balances, movement lines, reservations, picking, and reconciliation. Adding a tracking column to only the item master is insufficient.

## 8. Decisions required before physical schema

| ID | Decision | Required outcome |
|---|---|---|
| DB3-01 | User/company relationship | Confirm single-company user model and whether user may be assigned to multiple warehouses |
| DB3-02 | Location hierarchy | Confirm generic parent-child location versus separate zone/rack/bin entities |
| DB3-03 | UOM conversion | Confirm base-UOM-only launch or conversion table in P0 |
| DB3-04 | Inventory status | Approve exact initial statuses and availability semantics |
| DB3-05 | Inventory ledger | Choose transaction-line ledger or separate immutable ledger table |
| DB3-06 | Receiving posting | Decide whether stock posts at receiving/staging or after putaway |
| DB3-07 | Transfer lifecycle | Decide immediate transfer versus dispatch/receipt and in-transit |
| DB3-08 | Outbound lifecycle | Decide reservation, picking, and dispatch requirements |
| DB3-09 | Partial operations | Confirm partial receipt, putaway, transfer, pick, and shipment requirements |
| DB3-10 | Tracking dimensions | Confirm whether any lot/expiry/serial tracking is required in P0 |

## 9. Definition of ready for DB-04

DB-04 physical schema design may begin when:
- the P0 logical entities are accepted;
- each open decision above is resolved or explicitly deferred;
- inventory balance dimensions and posting model are stable;
- all operational workflows have defined stock posting boundaries;
- key uniqueness scopes and ownership paths are agreed;
- lifecycle and deletion rules are documented.

---

**Public documentation note:** This document is a proposed logical data model. It does not claim that the entities or workflows are implemented in the current application.