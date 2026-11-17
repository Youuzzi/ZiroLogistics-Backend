# WMS Warehouse Operations — Architecture & Delivery Contract

**Status:** Proposed implementation contract for roadmap Stage 9.  
**Runtime:** WMS-only modular monolith (`wms` profile).  
**Schema authority:** Frozen WMS V1 plus additive migrations only.  
**Verification:** Disposable MySQL 8.4 Testcontainers only.  
**Scope:** Inbound receiving, putaway, outbound picking/packing/shipping, and inter-warehouse stock transfers.

## 1. Stage objective

Stage 9 turns the Stage 7 warehouse/location master data and Stage 8 inventory core into executable warehouse workflows.

Operational documents are the business source of truth. Inventory is changed only through the existing `WmsInventoryPostingService`; workflow services must never write `inventory_balances`, `inventory_ledger`, or `inventory_serials` directly.

The frozen V1 schema already contains the principal operational tables. Do not recreate them, generate duplicate entities/tables, or edit V1–V12 migrations. First inspect exact DDL and implement against the existing schema. An additive migration is allowed only after a documented schema gap is demonstrated and reviewed.

## 2. Included workflows

### 2.1 Inbound receiving

Tables: `inbound_receipts`, `inbound_receipt_lines`, `inbound_receipt_line_lots`, `inbound_receipt_line_serials`.

Lifecycle:
- Create a DRAFT receipt for a tenant-owned supplier and warehouse, with a valid receiving location.
- Add/update lines only while the receipt is editable.
- Start receiving; record actual received quantity, accepted quantity and rejected quantity, where received = accepted + rejected.
- Capture lot and serial identities according to the item's tracking method.
- Complete receiving only when line quantities and traceability allocations reconcile.
- Post accepted stock once through the inventory posting service using RECEIPT at the receiving location. Rejected quantity never becomes available stock.
- Mark the receipt POSTED only in the same transaction as successful inventory posting; duplicate posting must be rejected.

Receipt statuses are constrained by V1: DRAFT, IN_PROGRESS, COMPLETED, POSTED, CANCELLED. The application defines legal transitions and guards terminal states. A COMPLETED receipt is not yet inventory-posted; POSTED is the irreversible inventory effect.

#### Inbound Receiving implementation boundary (first delivery)

The first API delivery uses a single atomic receive-and-post command rather than exposing a partially persisted receiving session:

- `GET /api/wms/v1/tenants/current/inbound-receipts` and `GET /{receiptPublicId}` read tenant-scoped receipts.
- `POST /api/wms/v1/tenants/current/inbound-receipts` creates a DRAFT receipt.
- `POST /{receiptPublicId}/lines` adds a base-UOM stock item line while the receipt remains DRAFT.
- `POST /{receiptPublicId}/post` supplies received/accepted/rejected quantities and required lot/serial traceability for every line. The service validates the complete receipt, moves the header through COMPLETED to POSTED inside one transaction, and delegates accepted stock to `WmsInventoryPostingService`. A failure rolls back receipt quantities, traceability links, inventory ledger/balances and audit together.
- A fully rejected receipt is allowed and creates no inventory movement.
- The first delivery supports PURCHASE and OTHER receipt types. RETURN requires a separate inspection/quarantine policy, and TRANSFER_IN is reserved for the inter-warehouse transfer workflow; both are deliberately rejected here rather than being incorrectly posted as ordinary available stock. A receipt with accepted quantities must have no more than 200 posting legs in this first delivery, matching the posting service's current command limit.
- When accepted stock exists, the command's request UUID is passed unchanged to the inventory ledger's tenant-scoped uniqueness guard. A fully rejected receipt has no inventory-ledger row; its terminal receipt state prevents reposting. Broader cross-workflow/payload-hash replay semantics remain Stage 11.
- Permissions introduced by V13 are `WMS_INBOUND_RECEIPT_READ`, `WMS_INBOUND_RECEIPT_CREATE`, `WMS_INBOUND_RECEIPT_UPDATE`, and `WMS_INBOUND_RECEIPT_POST`. Inventory posting additionally enforces `WMS_INVENTORY_POST`.

This deliberately keeps the first delivery atomic and avoids persisting serial-number staging data outside the inventory serial master. A future receiving-session workflow can be added only with a reviewed persistence model for incomplete receipts.

### 2.2 Putaway

Tables: `putaway_tasks`, `putaway_task_lines`, `putaway_confirmations`, `putaway_task_line_serials`.

Lifecycle:
- Generate or create a task from a completed/posted receipt's accepted quantities.
- Each line identifies receipt line, item, optional lot, receiving/source location, destination location and planned quantity.
- Validate destination belongs to the same tenant and warehouse, is active and suitable for storage; source and destination differ.
- Assign, start and confirm putaway quantities/serials.
- Confirmed quantities cannot exceed planned quantities or the unputaway accepted quantity for the source receipt line/lot.
- Each confirmed quantity is moved from receiving location to destination through one paired TRANSFER posting.
- Task/line completion and inventory posting commit atomically. Partial confirmations remain possible; over-confirmation and duplicate confirmation are rejected.

#### Putaway implementation boundary (first delivery)

- `GET /api/wms/v1/tenants/current/putaway-tasks` and `GET /{taskPublicId}` provide tenant-scoped task reads.
- `POST /api/wms/v1/tenants/current/putaway-tasks` creates a DRAFT task from a POSTED receipt. The caller explicitly allocates accepted receipt-line quantities to active storage destinations. LOT-tracked stock is allocated per receipt lot; SERIAL-tracked stock requires explicit serial assignment from the receipt.
- `POST /{taskPublicId}/ready`, `/assign`, and `/start` enforce the DRAFT → READY → ASSIGNED → IN_PROGRESS lifecycle. Only the assigned active tenant member may start or confirm the task.
- `POST /{taskPublicId}/lines/{linePublicId}/confirm` performs a partial or final location transfer through `WmsInventoryPostingService`. Source and destination ledger legs, confirmation row, line/task state and audit event commit in one transaction. The posting service remains the only writer of inventory balances, ledger and serial location state.
- Planned quantities cannot exceed accepted, not-yet-allocated receipt quantities. Confirmed quantities cannot exceed the task line's remaining planned quantity. Destination must be an active STORAGE, RACK, SHELF or BIN location; mixed-SKU and mixed-lot policies are checked both when planned and immediately before posting.
- A serial-tracked confirmation moves exactly one serial per request because the current inventory posting contract represents one TRANSFER as exactly two paired legs. A task may still contain many assigned serials and be confirmed in multiple requests.
- V14 adds `WMS_PUTAWAY_READ`, `WMS_PUTAWAY_CREATE`, `WMS_PUTAWAY_ASSIGN`, `WMS_PUTAWAY_START`, and `WMS_PUTAWAY_CONFIRM`, plus the putaway audit event allowlist. V1–V13 remain immutable.
- Confirmation request UUIDs are tenant-scoped and single-use in both putaway confirmation and inventory ledger persistence. Payload-hash replay semantics remain Stage 11.

### 2.3 Outbound order execution

Tables: `outbound_shipments`, `outbound_shipment_lines`, `outbound_shipment_allocations`, `outbound_shipment_line_serials`, `picking_waves`, `picking_wave_shipments`, `picking_tasks`, `picking_task_lines`, `picking_confirmations`, `picking_task_line_serials`, `packing_sessions`, `packing_packages`, `packing_package_lines`, `packing_package_serials`.

Shipment lifecycle:
- Create a DRAFT shipment for a tenant-owned customer and warehouse.
- Validate shipment lines, item/UOM, requested quantities and active warehouse references.
- Release only when the shipment is internally consistent and eligible for picking.
- Pick tasks allocate exact source locations, item, lot and serials. Reservations must use one controlled inventory application boundary; workflow code must not update reserved quantities or serial states directly.
- Confirm picked quantities and serials. Picked stock is moved to a designated staging/packing location through the inventory posting service. Picking cannot exceed the shipment's remaining quantity or the task allocation.
- Packing sessions group picked quantities into packages. Package contents must reconcile with picked quantities; a serial can be packed once only. Sealed packages cannot be edited.
- Shipment may be marked SHIPPED only after all required quantities are picked and packed and required package checks pass.
- Shipping posts the final ISSUE movement through the inventory posting service, then marks the shipment SHIPPED in the same transaction. Do not issue stock at pick time and again at ship time.

Shipment statuses are constrained by V1: DRAFT, RELEASED, PICKING, PICKED, PACKED, SHIPPED, CANCELLED. Picking task, packing session and package states must follow their exact V1 CHECK allowlists. Legal transitions are explicit and server-controlled.

### 2.4 Inter-warehouse transfer

Tables: `stock_transfers`, `stock_transfer_lines`, `stock_transfer_allocations`, `stock_transfer_line_serials`.

Lifecycle:
- Create a DRAFT transfer with distinct source and destination warehouses in the same tenant.
- Validate item, lot, serial and source/destination locations.
- Release/dispatch only when the requested quantities are available and allocated.
- Dispatch posts source-side stock movement through the inventory posting service and records the in-transit business state.
- Receive at the destination warehouse with accepted/short/damaged quantities supported by the actual V1 line model; do not invent unsupported states.
- Destination receipt posts the corresponding stock movement exactly once. Partial receipts are supported only where the V1 line/allocation schema and status checks support them.
- Transfer status and stock effects commit atomically; cancelled or terminal transfers cannot be reused.

Transfer statuses are constrained by V1: DRAFT, RELEASED, IN_TRANSIT, PARTIALLY_RECEIVED, RECEIVED, CANCELLED. A transfer is not a simple one-step location move when it crosses warehouses: in-transit ownership and dispatch/receipt semantics must be represented correctly. Do not implement it as an immediate paired TRANSFER unless the documented business model explicitly chooses a same-transaction warehouse move.

## 3. Shared invariants

### Tenant and authorization
- Resolve tenant, user and active membership from the authenticated WMS principal and persisted membership state.
- Never accept a client-supplied tenant ID, actor membership ID or internal numeric ID as authority.
- Resolve all warehouse, location, partner, item, UOM, document and membership references within the authenticated tenant.
- Use opaque public IDs in HTTP contracts. Cross-tenant references return safe not-found behavior.
- Define server-owned permissions per workflow action (read, create, update, receive/post, assign, confirm, release, pick, pack, ship, dispatch and receive transfer). Seed exact permission catalogue and owner grants only through additive migrations and repeatable provisioning updates. No wildcard permissions.

### Inventory and traceability
- The inventory posting service remains the only stock balance/ledger/serial mutation boundary.
- Workflow services send typed commands; they do not write inventory tables.
- Every inventory effect and its corresponding document state/confirmation/audit must commit in one transaction.
- Use BigDecimal only; respect DECIMAL(19,4), item UOM precision and explicit conversion rules. Never silently round.
- Lot/serial allocations must reconcile to line quantities. No duplicate serial allocation, cross-item lot, cross-tenant identity, or serial movement without a valid state transition.
- Rejected inbound goods, in-transit transfer quantities, packed goods and shipped goods must not be double-counted as available stock.
- Use existing reservation fields and serial lifecycle only through a reviewed inventory application boundary; never add an alternate stock/reservation table without a schema decision.

### Lifecycle, concurrency and audit
- Define a transition matrix for every document, task, line, confirmation and package state before implementation.
- Lock in a documented global order compatible with Stage 8: tenant → warehouses sorted → locations sorted → items sorted → lots sorted → serials sorted → balance buckets sorted → workflow documents/lines in deterministic order. If a workflow aggregate lock must precede a shared reference, revise and prove the global order before coding.
- Use optimistic version checks for mutable headers where V1 provides a version column. Use guarded updates/row locks for lines and confirmations.
- Unique document/task/package numbers and database constraints are final protection; map conflicts to safe HTTP 409 responses.
- Every successful state transition and stock effect writes tenant audit events in the same transaction. Audit failure rolls back the operation.
- A request UUID must be single-use per tenant/workflow command. Stage 9 must not claim payload-hash replay protection unless a durable idempotency registry is implemented and verified. Stage 11 owns broader cross-workflow retry/idempotency guarantees.

## 4. HTTP/API boundary

Base path: `/api/wms/v1/tenants/current`.

Use separate controllers/application services by bounded workflow:
- `/inbound-receipts`
- `/putaway-tasks`
- `/outbound-shipments`
- `/picking-waves` and `/picking-tasks`
- `/packing-sessions`
- `/stock-transfers`

Prefer explicit action endpoints for state transitions (receive, complete, post, assign, start, confirm, release, dispatch, pack, seal, ship and receive transfer) rather than generic status updates. Request DTOs expose only editable business fields and expected versions; response DTOs expose public IDs, document numbers, state and bounded line details. No generic CRUD endpoint may bypass the lifecycle.

## 5. Delivery order

1. Inspect and map every relevant V1 operational table, FK, CHECK, unique key and trigger-like generated column; record the exact schema contract.
2. Define permissions, transition matrices, posting semantics and transaction boundaries for each workflow.
3. Implement inbound receipt draft/line/receiving and atomic receipt posting.
4. Implement putaway task creation, assignment and partial/full confirmations.
5. Implement outbound shipment lifecycle and picking allocation/reservation/confirmation.
6. Implement packing sessions/packages and atomic shipment issue.
7. Implement inter-warehouse transfer dispatch and destination receipt.
8. Add tenant-isolation, authorization, state-transition, rollback, duplicate-request, partial-quantity, lot/serial and MySQL concurrency tests.
9. Run the full CI gate; merge only after review and successful CI.

Delivery may be split into multiple focused PRs. Do not implement all workflows in one oversized change.

## 6. Verification and safety gates

- Tests use disposable MySQL 8.4 Testcontainers only. Never connect to, inspect, or mutate the user's MySQL Workbench databases.
- Verify both fresh V1→latest migration and latest migration upgrade paths; do not edit V1–V12.
- Test transaction rollback when inventory posting, audit insertion, confirmation insertion or document-state update fails.
- Test cross-tenant references, missing permissions, invalid transitions, stale versions, duplicate request IDs, over-receipt, over-putaway, over-pick, over-pack, over-ship, and insufficient available stock.
- Test lot/serial reconciliation and concurrent confirmation/posting attempts.
- Do not claim a workflow complete until its integration tests run against disposable MySQL and CI passes.

## 7. Explicit exclusions

- Cycle counting, stocktake variance approval and inventory reconciliation belong to Stage 10.
- Replenishment planning/rules and replenishment task execution belong to Stage 10 unless a dependency is explicitly approved.
- Broader retry orchestration, payload-hash idempotency registry and cross-workflow concurrency policy belong to Stage 11.
- Reporting/analytics and frontend work are outside Stage 9.
- No changes to the legacy application profile or legacy entities.
