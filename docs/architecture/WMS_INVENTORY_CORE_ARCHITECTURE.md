# WMS Inventory Core — Architecture & API Contract

**Status:** Proposed implementation contract for roadmap Stage 8.  
**Runtime:** WMS-only modular monolith.  
**Schema authority:** Frozen WMS V1 plus additive migrations only (V11 onward).  
**Verification:** Disposable MySQL 8.4 Testcontainers only.  
**Scope:** Item/category master data, UOM and item conversions, item identifiers, lot/serial identity, inventory balance/ledger core and tenant-scoped inventory queries.

## 1. Stage objective and boundaries

Stage 8 establishes the inventory domain foundation used by all later warehouse workflows. It must provide one authoritative item identity, one base stock UOM per item, traceable lot/serial identity, a single stock-balance model and an append-only movement ledger.

This stage does **not** implement inbound receiving, putaway, stock transfer workflows, cycle counts, reservations as a business workflow, picking, packing, shipment, replenishment or reporting. Those belong to later roadmap stages. It must provide the domain services and invariants those workflows will call; later modules must not write inventory tables directly.

Do not create a second stock table, alternate stock ledger, or per-workflow stock balance. `inventory_balances` is the sole operational balance projection and `inventory_ledger` is the immutable movement history.

## 2. Frozen V1 schema inventory

The following existing V1 tables are authoritative; do not edit V1 or regenerate these structures:

| Area | Existing tables | Ownership |
|---|---|---|
| Category and item master | `item_categories`, `items`, `item_identifiers`, `item_uom_conversions` | Tenant-scoped |
| Unit catalogue | `units_of_measure` | Global reference catalogue |
| Traceability | `inventory_lots`, `inventory_serials` | Tenant + item scoped |
| Stock core | `inventory_balances`, `inventory_ledger` | Tenant + warehouse + location + item scoped |

The schema contains the required keys and constraints, including tenant-aware composite foreign keys, tenant/SKU uniqueness, identifier uniqueness, lot and serial uniqueness, balance bucket uniqueness, generated available quantity, ledger request-entry uniqueness, non-negative stock constraints and ledger before/after arithmetic checks.

The DDL is the authority for every column, type, nullability, CHECK allowlist, index and foreign key. Implementation must inspect the complete DDL before writing SQL. No blind entity generation.

## 3. Domain invariants

### 3.1 Tenant and reference ownership

- Resolve tenant scope only from the authenticated WMS principal and persisted membership permissions.
- Never accept a client-supplied tenant ID as authorization.
- Every tenant-owned item, category, identifier, conversion, lot, serial, balance and ledger query includes the resolved tenant predicate.
- UOM rows are global reference data and have no tenant ID. They are readable from the WMS catalogue but are not tenant-owned.
- Every warehouse/location reference must resolve inside the same tenant and warehouse; use the composite FK model already present in V1.
- Public IDs are opaque; never expose internal numeric primary keys in API responses.

### 3.2 Item and category master

- SKU is trimmed and normalized consistently (uppercase with Locale.ROOT); uniqueness is enforced by the database per tenant.
- Category codes are tenant-unique. Parent categories must belong to the same tenant. Reject self-parenting and cycles.
- Items reference one active base UOM. A category, when supplied, must be active and tenant-owned.
- Tracking method is exactly `NONE`, `LOT`, `SERIAL` or `LOT_AND_SERIAL`; status is exactly `ACTIVE`, `INACTIVE` or `DISCONTINUED`.
- Stock-affecting item attributes (base UOM, tracking method and stock-item classification) become immutable once any balance or ledger row exists. Do not silently reinterpret historical stock.
- Deactivation/discontinuation is blocked while the item has non-zero on-hand/reserved stock or open operational references. Until later workflow modules exist, enforce only checks backed by existing tables and do not claim to detect future workflow states.
- Non-stock items cannot receive inventory balances or ledger movements.

### 3.3 UOM and conversion

- `units_of_measure` is a global controlled catalogue. Stage 8 exposes read-only lookup/list APIs; no tenant endpoint may create or mutate global UOM definitions.
- Supported dimensions are the exact V1 CHECK values: `COUNT`, `MASS`, `LENGTH`, `AREA`, `VOLUME`, `TIME`.
- An item base UOM and all item-specific conversion endpoints must share the same dimension.
- Conversion factor semantics are fixed: **1 unit of from_uom = conversion_factor units of to_uom**.
- Conversion uses BigDecimal only; never binary floating point. Apply the target UOM decimal-place policy explicitly and reject precision loss unless the calling workflow explicitly requests an approved rounding mode.
- No implicit conversion path search or guessed conversion. Only an explicitly configured direct conversion is valid. A reverse conversion is not assumed unless the reverse row exists.
- A conversion pair must not be duplicated. Conversion changes are audited; once referenced by posted operational documents, a conversion factor is not edited in place. Use a controlled deactivate/replacement policy and preserve transaction-line conversion snapshots in the relevant later workflow tables.

### 3.4 Identifiers

- Identifier types are exactly `BARCODE`, `QR_CODE`, `GTIN`, `ALTERNATE_SKU`.
- Identifier values are trimmed and normalized under a documented type-specific policy; preserve leading zeroes (especially GTIN/barcodes).
- Identifier uniqueness is tenant-wide per identifier type. A duplicate is a safe conflict, not an overwrite.
- At most one primary identifier per item should be enforced by the application transactionally; V1 has no partial unique constraint for this rule.
- Identifier lookup is tenant-scoped and must return only active items.

### 3.5 Lot and serial traceability

- Lot numbers are unique per tenant/item. Manufacture, received and expiry dates must respect the V1 CHECK constraints.
- Lot statuses are exactly `ACTIVE`, `QUARANTINED`, `BLOCKED`, `EXPIRED`, `CLOSED`. Status changes are explicit and audited.
- Serial numbers are unique per tenant. A serial belongs to one tenant/item and optionally one lot of the same item.
- Serial statuses are exactly `AVAILABLE`, `RESERVED`, `PICKED`, `SHIPPED`, `DAMAGED`, `QUARANTINED`, `RETURNED`, `LOST`; transitions are explicit and validated.
- Serial location fields are either both null or both populated. Any populated location must belong to the same tenant/warehouse.
- `SERIAL` and `LOT_AND_SERIAL` items require serial identity on every applicable stock movement; a serial movement quantity is exactly one unit in absolute value, as enforced by V1 ledger CHECK.
- `LOT` and `LOT_AND_SERIAL` movements require a valid lot; `NONE` and `SERIAL` movements must not invent a lot.
- Aggregate serial counts and lot allocations must reconcile with balance quantities. Serial status/location and ledger movement must be committed in the same transaction as the balance change.

### 3.6 Inventory balance

- `inventory_balances` is the only current stock projection.
- A balance bucket is uniquely identified by tenant, warehouse, location, item, lot (null represented by the generated lot key) and stock status.
- Quantities use DECIMAL(19,4), are non-negative, and `reserved_qty` cannot exceed `on_hand_qty`.
- `available_qty` is database-generated as on-hand minus reserved; never write to it.
- Only `AVAILABLE` stock may carry a non-zero reserved quantity under the existing CHECK.
- Stock statuses are exactly `AVAILABLE`, `QUARANTINED`, `DAMAGED`, `EXPIRED`, `BLOCKED`.
- Zero-quantity buckets may be retained for history/reference stability; deletion policy must be explicit and must never remove ledger history.
- A balance row is created/updated only inside the inventory posting transaction. No controller, repository or workflow module may mutate it directly.

### 3.7 Immutable inventory ledger

- Every stock quantity change appends ledger entries in the same transaction as balance changes.
- Ledger rows are append-only: no update or delete path is exposed.
- Each entry has a non-zero delta and must satisfy `balance_after_qty = balance_before_qty + quantity_delta`.
- Movement types must use the exact V1 CHECK allowlist: `RECEIPT`, `ISSUE`, `TRANSFER`, `ADJUSTMENT_IN`, `ADJUSTMENT_OUT`, `RETURN`, `DAMAGE`, `STATUS_CHANGE`, `OPENING_BALANCE`.
- A movement group may contain multiple entries. Internal transfers require paired source-negative and destination-positive entries with the same movement group, and net quantity zero for each item/lot/stock-status combination.
- `request_id` plus `entry_no` is the database idempotency uniqueness boundary per tenant. Reuse of a request ID must be detected before any new balance mutation. A retry may return the prior committed result only when the incoming canonical request matches the stored ledger facts; a conflicting reuse is rejected. Do not claim payload-hash protection because V1 has no request fingerprint column/table.
- The actor is the authenticated membership resolved by the backend, never a client-supplied actor ID.
- Reference type/IDs are server-populated from the calling workflow; clients cannot forge document ownership.

## 4. Transaction and concurrency protocol

All stock mutations go through one WMS application service (for example, `WmsInventoryPostingService`) with a single transaction boundary. Later receiving, transfer, adjustment, count and replenishment modules call this service through a typed application port.

Use a consistent lock order across every stock-changing path:

1. Resolve and validate persisted tenant membership/permission.
2. Lock the tenant row (same serialization boundary already used by WMS master-data mutations).
3. Lock warehouse rows in ascending internal ID.
4. Lock location rows in ascending internal ID.
5. Lock item row(s) in ascending internal ID.
6. Lock lot row(s), then serial row(s), in ascending internal ID.
7. Lock existing balance bucket rows in deterministic key order; create missing buckets with unique-key-safe insert/retry semantics.
8. Append ledger rows and update balance/serial state before commit.

The exact lock set may be narrower for read-only operations, but all writers must preserve a compatible global order. Deadlocks are handled by a bounded transaction retry only when the operation is safe and its request ID is unchanged. Never retry arbitrary partial work outside the transaction.

For a movement with multiple legs, calculate all deltas and validate all resulting balances before applying any leg. The transaction either commits all balance, ledger, lot/serial and audit changes or commits none.

The Stage 7 warehouse/location status checks and Stage 8 inventory writers must share the same tenant/warehouse/location locking protocol. This closes the race where a warehouse/location is closed while another transaction posts stock into it. Do not claim this cross-module guarantee until integration tests prove both paths contend on the same locks.

## 5. Authorization and audit catalogue

Proposed permission codes (global catalogue entries, not client-created strings):

- `WMS_ITEM_READ`, `WMS_ITEM_CREATE`, `WMS_ITEM_UPDATE`, `WMS_ITEM_STATUS_MANAGE`
- `WMS_ITEM_CATEGORY_READ`, `WMS_ITEM_CATEGORY_MANAGE`
- `WMS_ITEM_IDENTIFIER_MANAGE`, `WMS_ITEM_UOM_CONVERSION_MANAGE`
- `WMS_UOM_CATALOG_READ`
- `WMS_LOT_READ`, `WMS_LOT_MANAGE`
- `WMS_SERIAL_READ`, `WMS_SERIAL_MANAGE`
- `WMS_INVENTORY_READ`, `WMS_INVENTORY_LEDGER_READ`

Posting permissions for receiving, transfer, adjustment, stock count and reservations are owned by their respective later workflow stages; Stage 8 must not grant broad inventory mutation rights prematurely.

Existing and newly provisioned `TENANT_OWNER` memberships receive the explicit Stage 8 read/master-data permission allowlist. Only explicitly designated read/create/update permissions may be delegated to custom roles. Status, lot/serial lifecycle and ledger/posting privileges remain separately controlled.

Proposed tenant audit event types:

- `ITEM_CATEGORY_CREATED`, `ITEM_CATEGORY_UPDATED`, `ITEM_CATEGORY_STATUS_CHANGED`
- `ITEM_CREATED`, `ITEM_UPDATED`, `ITEM_STATUS_CHANGED`
- `ITEM_IDENTIFIER_ADDED`, `ITEM_IDENTIFIER_UPDATED`, `ITEM_IDENTIFIER_REMOVED`
- `ITEM_UOM_CONVERSION_CREATED`, `ITEM_UOM_CONVERSION_DEACTIVATED`
- `INVENTORY_LOT_CREATED`, `INVENTORY_LOT_STATUS_CHANGED`
- `INVENTORY_SERIAL_REGISTERED`, `INVENTORY_SERIAL_STATUS_CHANGED`

Inventory quantity changes are primarily evidenced by the immutable ledger; do not duplicate every ledger row into the tenant audit log. Add audit types through V11 and preserve every existing V8–V10 CHECK allowlist entry.

## 6. API contract

Base path: `/api/wms/v1/tenants/current`.

- `GET /uoms` — global active UOM catalogue, optionally filtered by dimension.
- `GET /item-categories` and `POST /item-categories`.
- `GET /item-categories/{categoryPublicId}`, `PUT /item-categories/{categoryPublicId}`, and explicit category status action.
- `GET /items`, `POST /items`, `GET /items/{itemPublicId}`, `PUT /items/{itemPublicId}`, and explicit item status action.
- `GET /items/{itemPublicId}/identifiers`, add/update/remove identifier operations.
- `GET /items/{itemPublicId}/uom-conversions`, add/deactivate conversion operations.
- `GET /items/lookup?identifierType=...&value=...` — tenant-scoped active-item lookup.
- `GET /lots`, `POST /lots`, `GET /lots/{lotPublicId}`, and explicit lot status action.
- `GET /serials`, `POST /serials`, `GET /serials/{serialPublicId}`; status changes only through authorized workflow services.
- `GET /inventory/balances` — bounded, filterable, tenant-scoped current balances.
- `GET /inventory/ledger` — bounded, cursor/time-filtered immutable movement history.

All list endpoints require bounded pagination and stable ordering. Filter values are validated; no unbounded table scans or client-selected tenant filters. Responses expose public IDs, business fields, quantities and versions only; internal IDs and credentials are never returned.

No public generic endpoint may post arbitrary ledger deltas. No manual balance edit endpoint is introduced in Stage 8. Future workflow endpoints must submit typed business commands to the inventory posting service.

## 7. V11 migration and verification gates

V1–V10 are immutable. Stage 8 may add V11 only for the explicit permission catalogue/owner grants and tenant-audit event CHECK expansion. Do not add duplicate inventory tables; the complete stock schema already exists in V1.

Before implementation merge, disposable MySQL 8.4 Testcontainers must verify:

1. Fresh V1→V11 and existing V10→V11 migration paths validate; earlier migration checksums remain unchanged.
2. Exact Stage 8 permission catalogue entries and owner grants for both existing and newly provisioned tenants.
3. Tenant isolation for category, item, identifier, conversion, lot, serial, balance and ledger reads/writes.
4. Category parent self-reference/cycle prevention and cross-tenant rejection.
5. SKU, identifier, lot and serial uniqueness under concurrent creation.
6. UOM dimension mismatch, invalid decimal precision, zero/negative conversion factors and ambiguous/missing conversion rejection.
7. Item stock-defining fields cannot change after balance/ledger history exists.
8. Lot date/status and serial tracking invariants, including item/lot ownership.
9. Balance generated available quantity, reserved/on-hand CHECKs, stock-status rules and unique bucket behavior.
10. Ledger append-only behavior, before/after arithmetic, movement-group pairing, serial quantity rule and request/entry uniqueness.
11. Concurrent competing issues cannot drive a balance negative; exactly one conflicting transaction succeeds where stock is insufficient.
12. A multi-leg movement that fails on any leg leaves no partial balance or ledger writes.
13. Warehouse/location closure racing with stock posting is serialized using the shared lock protocol.
14. Audit insertion failure rolls back item/lot/serial metadata mutation; ledger/balance mutation is atomic.
15. HTTP tests cover authentication, persisted permissions, validation, safe not-found and bounded pagination.

No tests may connect to a user's local, development or production database. Do not request or expose DB credentials or real account data.

## 8. Explicit non-goals and handoff

- Inbound receiving, putaway, transfers, adjustments, stock counts, reservations, picking, packing, shipment and replenishment.
- Supplier/customer workflows and purchase/sales order lifecycle.
- Costing, valuation, accounting journals and financial posting.
- Forecasting, replenishment rules and reporting aggregates.
- Physical deletion of items/categories/lots/serials or ledger history.
- Per-user warehouse assignment; frozen V1 has no warehouse-membership assignment relation.
- Production DB migration execution or manual data mutation.

Stage 9 consumes the item/UOM/lot/serial and inventory posting contracts to implement warehouse operations. Stage 10 adds inventory control workflows. Stage 11 hardens cross-workflow idempotency, concurrency, deadlock recovery and reconciliation based on real workflow paths.
