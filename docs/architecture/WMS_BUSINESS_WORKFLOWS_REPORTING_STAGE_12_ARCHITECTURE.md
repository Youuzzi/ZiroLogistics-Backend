# WMS Business Workflows & Reporting — Stage 12 Architecture & Delivery Contract

**Status:** Proposed implementation contract for roadmap Stage 12.
**Runtime:** WMS-only modular monolith (`wms` profile).
**Schema authority:** Frozen WMS V1 plus additive migrations V2–V26; future schema changes are additive V27+ only.
**Verification:** Disposable MySQL 8.4 Testcontainers only.
**Baseline commit:** `8cf37a7558a07481d284d91b2b137f28ed61d8cd`.
**Scope:** End-to-end integrity across inbound, outbound, inventory lifecycle, inter-warehouse transfer, replenishment/stock control, operational reporting and audit traceability.

## 1. Stage objective

Stage 12 verifies that the WMS modules delivered in Stages 7–11 operate as coherent business workflows. It closes only demonstrated cross-module gaps; it is not a broad feature-expansion stage.

Operational documents remain the business source of truth. Inventory state changes remain exclusively owned by `WmsInventoryPostingService`. Workflow services must not write `inventory_balances`, `inventory_ledger`, or `inventory_serials` directly.

Every workflow must preserve:
- authenticated tenant and membership scope; no client-selected tenant authority;
- persisted permission checks at the relevant application boundary;
- explicit document state transitions and legal transition guards;
- transaction atomicity between document state, inventory posting, traceability and audit;
- item/UOM/lot/serial/location ownership and quantity reconciliation;
- idempotency and concurrency behavior established in Stage 11;
- append-only inventory ledger and tenant audit evidence;
- no cross-tenant joins or disclosure through list, detail, report or export queries.

## 2. Verified implementation baseline

The following are source-structure findings at the baseline commit. **VERIFIED here means the source files/routes/services exist in the repository; it does not claim every end-to-end business scenario has already passed runtime verification.** Stage 12 WPs must inspect implementation and existing tests before deciding whether code changes are necessary.

### 2.1 Existing workflow modules

| Workflow | Existing implementation | Existing business boundary |
|---|---|---|
| Inbound receiving | `WmsInboundReceivingService`, `WmsInboundReceivingController` | Receipt creation/lines and atomic receive-and-post command; accepted quantities post through inventory posting; rejected quantities do not become available stock |
| Putaway | `WmsPutawayManagementService`, `WmsPutawayManagementController` | Tasks from posted receipts; assign/start/confirm; confirmed quantity moves from receiving to storage through paired TRANSFER posting |
| Outbound shipment | `WmsOutboundShipmentService`, `WmsOutboundShipmentController` | Shipment/lines/allocations and release |
| Picking | `WmsPickingManagementService`, `WmsPickingManagementController` | Assigned task and confirmation; picked stock moves to staging through inventory posting |
| Packing | `WmsPackingManagementService`, `WmsPackingManagementController` | Sessions, packages, package lines, serial evidence, seal and completion |
| Shipping | `WmsShippingManagementService`, `WmsShippingManagementController` | Validates packed shipment and posts final ISSUE before marking shipment shipped |
| Inter-warehouse transfer | `WmsInterWarehouseTransferService`, `WmsInterWarehouseTransferController` | Release, dispatch and receive; paired transfer movements and serial in-transit handling |
| Stock count | `WmsStockCountManagementService`, `WmsStockCountManagementController` | Planning, snapshot/freeze, result recording, submit, line review, approval and cancellation |
| Inventory adjustment | `WmsInventoryAdjustmentService`, `WmsInventoryAdjustmentController` | Physical-count-linked and manual reason-coded adjustment; submit/approve/reject/post |
| Replenishment | `WmsReplenishmentPlanningService`, `WmsReplenishmentTaskService`, related controllers | Rule-based planning and task execution; confirmation and one-time reversal |

The existing source tree also contains inventory query, reservation, posting and warehouse management services. Existing MySQL integration test classes cover inbound, putaway, outbound shipment, inventory posting, stock count, replenishment, transfer-related operations and tenant/runtime boundaries.

### 2.2 Reporting baseline and explicit gap

The repository contains a legacy `/api/v1.0/dashboard/summary` endpoint backed by the legacy `DashboardServiceImpl`, which counts legacy warehouses, bins and items.

The WMS runtime entry point `WmsRuntimeApplication` scans only `com.zirocraft.zirologistics.wms`. At this baseline there is no WMS reporting package/controller/service in that runtime package. WP7 adds `WmsOperationalReportingService` and `/api/wms/v1/tenants/current/reports/operational-summary`, protected by persisted `WMS_OPERATIONAL_REPORT_READ`. Therefore the legacy dashboard is **not** an acceptable substitute for tenant-scoped WMS operational reporting.

Stage 12 WP7 must design and implement reporting within the WMS runtime, using tenant-scoped WMS sources and persisted permissions. Do not expose legacy aggregate counts as WMS reports, and do not introduce cross-tenant or unscoped aggregation.

### 2.3 Important scope boundary

The existing operational modules are not proof that all business workflows are complete. Conversely, the existence of a legacy dashboard or a missing report endpoint is not permission to build an unrestricted analytics platform.

Do not introduce purchase-order/procurement workflows, supplier settlement, carrier integrations, accounting, forecasting/AI, advanced labor management, or a new event platform in Stage 12 unless a concrete dependency or correctness gap is demonstrated and separately justified.

Do not create duplicate inventory balances, alternate ledgers, parallel document state stores, or a second posting engine.

## 3. Canonical end-to-end workflow contracts

### 3.1 Inbound to available storage

Expected business chain:

`Supplier / approved source → Inbound Receipt → Receive & Validate → Inventory Receipt Posting → Putaway Task → Putaway Confirmation → Storage Location`

Required integrity:
- receipt accepted + rejected = received for every line;
- only accepted stock is posted; rejected quantity never becomes available;
- lot/serial traceability reconciles exactly with accepted quantities;
- receipt terminal state and inventory posting commit atomically;
- putaway allocation cannot exceed accepted, not-yet-putaway receipt quantity;
- putaway confirmation moves the exact quantity/serial from receiving to an eligible storage location;
- no double receipt or double putaway; document state, request identity, ledger and audit remain consistent;
- partial putaway must remain accurately represented and reportable.

### 3.2 Outbound order to shipped

Expected business chain:

`Outbound Shipment → Release / Allocation → Picking Task → Pick Confirmation to Staging → Packing Session / Packages → Seal & Complete → Shipping Validation → Final ISSUE Posting → SHIPPED`

Required integrity:
- allocations cannot exceed eligible available/reservable stock or shipment demand;
- picked quantity and serial evidence reconcile with shipment allocations;
- picking transfers stock to staging; it does not issue stock;
- package contents reconcile with picked quantities; serials cannot be packed twice;
- sealed packages cannot be edited;
- shipping requires all required picking/packing/package checks to pass;
- final ISSUE and shipment SHIPPED state commit atomically;
- failed shipping must not leave partial issue, package or shipment state;
- partial, cancelled and rejected paths must not silently lose stock or demand.

### 3.3 Inventory lifecycle and stock control

Expected business chain:

`Inventory Receipt / Transfer / Issue → Balance & Ledger → Reservation / Operational Consumption → Stock Count Snapshot & Freeze → Count Results / Review / Approval → Inventory Adjustment → Reconciled Balance & Ledger`

Required integrity:
- every stock mutation is represented in the single posting service and immutable ledger;
- reservations reduce available quantity without creating a ledger movement;
- count scope and freeze boundary prevent inconsistent concurrent movements;
- blind-count permissions do not expose expected quantities to unauthorized counters;
- review/approval separation and adjustment linkage remain four-eyes controlled;
- manual adjustment reason/direction rules remain enforced;
- posting an adjustment revalidates current baseline and frozen/reserved stock constraints;
- variance and adjustment evidence remains traceable from count to ledger.

### 3.4 Inter-warehouse transfer

Expected business chain:

`Transfer Request → Release → Source Dispatch → In-Transit → Destination Receipt / Reconciliation → Completed`

Required integrity:
- source and destination warehouses belong to the same tenant and differ;
- dispatch removes stock from source through the approved posting boundary;
- received stock is posted only after destination validation;
- serial identity has exactly one valid lifecycle/location outcome;
- shortages, overages, partial receipt and reversal/reconciliation paths preserve source, transit, destination, ledger and audit consistency;
- no duplicate stock movement on retries or concurrent receive/reconcile requests.

### 3.5 Replenishment and stock-control integration

Expected business chain:

`Replenishment Rule / Manual Demand → Plan → Task Release & Assignment → Start → Confirmation → Inventory Transfer Posting → Optional One-Time Reversal`

Required integrity:
- planning is not stock reservation or posting;
- source availability is checked against aggregate demand, including multiple lines targeting the same source bucket;
- confirmation moves exact quantity/lot/serial between eligible locations;
- partial confirmation and remaining demand are explicit;
- cancellation and reversal are state-guarded and auditable;
- stock count freezes and reservations cannot be bypassed by replenishment;
- task status, confirmation records, inventory ledger and reporting remain consistent.

## 4. Stage 12 work packages

### WP1 — Workflow Architecture & Baseline Audit

- Establish this architecture/delivery contract and canonical end-to-end workflow definitions.
- Map existing services, controllers, persistence tables, permission checks, audit events and integration tests.
- Record which links are implemented, which have runtime evidence, and which remain unverified.
- Identify only concrete workflow gaps; defer unrelated API standardization to Stage 13.
- Record the WMS reporting gap and define tenant-safe report boundaries.
- No production feature or schema changes unless a blocking architecture defect is discovered and separately approved.

### WP2 — Inbound Workflow Integrity

- Audit receipt → posting → putaway continuity, including partial quantities and rejected quantities.
- Verify lot/serial, receiving-location, putaway destination and source-receipt reconciliation.
- Verify duplicate commands, failure rollback, cancellation/terminal-state guards and tenant isolation.
- Add only missing focused MySQL integration evidence or narrowly scoped fixes.

### WP3 — Outbound Workflow Integrity

- Audit shipment → allocation → picking → staging → packing → shipping continuity.
- Verify demand, allocation, picked, packed, shipped and serial quantities reconcile at each transition.
- Verify no ISSUE at picking and exactly one final ISSUE at shipping.
- Verify package sealing, partial work, cancellation and failed-posting rollback.
- Add only missing focused MySQL integration evidence or narrowly scoped fixes.

### WP4 — Inventory Lifecycle Integration

- Audit how receipt, putaway, picking, shipping, transfer, count and adjustment use the single inventory posting boundary.
- Verify balance/ledger/serial consistency and the relationship between available, reserved and physical quantities.
- Verify status/lot/serial constraints survive cross-workflow transitions.
- Do not duplicate stock projections or bypass posting service.

### WP5 — Inter-Warehouse Workflow

- Audit request → dispatch → transit → receipt/reconciliation as one business process.
- Verify partial receipt, shortage handling, serial lifecycle, reversal and retry behavior.
- Verify source/destination tenant and warehouse boundaries and audit traceability.
- Fix only demonstrated gaps.

### WP6 — Replenishment & Stock Control Integration

- Audit planning, task execution, confirmation, cancellation/reversal and interaction with reservations and count freezes.
- Verify aggregate source demand, partial confirmations, lot/serial handling and inventory posting.
- Verify replenishment cannot bypass count freeze or consume reserved stock.
- Keep planning side-effect-free with respect to inventory until confirmation.

### WP7 — Operational Reporting

- Add a dedicated WMS reporting boundary inside the WMS runtime. WP7 delivers a read-only operational summary across inventory position, inbound receipts, outbound shipments, inter-warehouse transfers, replenishment tasks, stock counts and adjustments.
- Define tenant-scoped, permission-protected operational summaries based on authoritative WMS documents and inventory sources.
- Start with actionable operational views: inventory position/availability, inbound receiving and putaway progress, outbound picking/packing/shipping progress, transfer status, replenishment workload, stock-count/adjustment status.
- Define time basis, status semantics, filters, pagination/limits and aggregation behavior explicitly.
- Reports are read-only; no report query may mutate stock or workflow state.
- Avoid unbounded scans and expensive cross-domain joins; inspect indexes and query plans before widening report scope.
- Keep legacy dashboard behavior isolated; do not silently repurpose legacy repositories for WMS data.

### WP8 — Workflow Audit & Traceability

- Verify every material state transition and inventory-affecting action has tenant-scoped actor/time/document evidence.
- Trace document IDs through linked workflow documents, confirmations, posting request IDs and ledger entries.
- Verify audit events are atomic with the business transaction and cannot be forged by client-supplied actor/tenant values.
- Add focused traceability queries or read models only when a concrete operational need exists.
- Preserve append-only ledger and audit semantics.

### WP9 — Final Workflow Integration & CI

- Run a final end-to-end gap review across all Stage 12 workflows and reports.
- Add deterministic MySQL 8.4 Testcontainers tests for any uncovered cross-module boundaries.
- Verify tenant isolation, permission denial, idempotency, concurrency, rollback, serial/lot reconciliation and audit completeness.
- Run full Maven regression for code, SQL, configuration, dependencies, tests or workflow changes.
- Merge only after PR CI succeeds and review findings are resolved; verify post-merge Main CI on the exact merge SHA.
- Stage 12 is complete only after all nine WPs are merged and final Main CI succeeds.

## 5. Engineering and schema rules

1. V1–V25 migrations are immutable. WP7 adds V26 for the WMS operational reporting permission; future schema extensions must be additive V27+.
2. Never run migrations or schema-changing commands against the user's local databases. Use disposable MySQL 8.4 Testcontainers in CI.
3. Never write inventory balances, ledger, serial state or reservations from controllers or workflow modules; use the existing application boundary.
4. Preserve tenant scope and persisted permission resolution in every query and command.
5. Preserve document state, inventory posting, traceability and audit atomically.
6. Do not add automatic transaction retries unless the operation's idempotency and replay safety are explicitly proven.
7. Do not reduce, skip, weaken or remove tests to accelerate CI.
8. Do not broaden Stage 12 into procurement, accounting, carrier integration or advanced analytics without a documented dependency and separate scope decision.
9. Keep PRs focused, branch-based and reviewed; never push directly to `main`.
10. Do not start frontend implementation during Stage 12 or Stage 13.

## 6. Definition of Done

Stage 12 is complete when:
- all five operational chains above have been audited against actual code and deterministic integration evidence;
- all demonstrated workflow gaps are fixed without duplicate domain state or bypassing inventory posting;
- WMS reporting exists within the WMS runtime, is tenant-scoped and permission-protected, and uses authoritative WMS sources;
- document-to-ledger and actor-to-audit traceability is verified;
- cross-module failure, concurrency, retry and tenant-isolation behavior is covered by focused tests;
- all nine work packages are merged;
- final full regression and post-merge Main CI succeed on the exact main commit.

## 7. Evidence labels

Use the following labels in WP audit notes and PR descriptions:
- **VERIFIED** — directly demonstrated by deterministic test/runtime evidence.
- **PARTIALLY VERIFIED** — evidence covers only part of the stated behavior or boundary.
- **INFERRED** — supported by code structure/inspection, but not proven by runtime evidence.
- **UNVERIFIED** — no adequate evidence found yet.
- **PROPOSED** — intended design or future behavior, not implemented.

Never promote code-structure inspection to runtime verification. Every gap should identify the exact workflow boundary, source location, missing evidence and expected invariant before implementation.
