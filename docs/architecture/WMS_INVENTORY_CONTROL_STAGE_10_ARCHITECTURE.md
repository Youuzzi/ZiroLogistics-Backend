# WMS Inventory Control — Stage 10 Architecture & API Contract

**Status:** Proposed implementation contract for roadmap Stage 10.  
**Runtime:** WMS-only modular monolith.  
**Schema authority:** Frozen WMS V1 plus additive migrations only (V20 onward).  
**Verification:** Disposable MySQL 8.4 Testcontainers only.  
**Scope:** Stock counts, cycle counting, variance review/approval, inventory adjustments and warehouse replenishment.

## 1. Stage objective and boundaries

Stage 10 adds controlled inventory verification and correction workflows on top of the single inventory balance and immutable ledger established in Stage 8 and the warehouse operations established in Stage 9.

The stage has four deliverables:

1. Physical stock count planning, location scopes, quantity snapshots, count rounds and recounts.
2. Review and approval of count results, including a traceable variance decision.
3. Inventory adjustment documents that translate approved differences into stock postings.
4. Replenishment rules and tasks that move stock between locations inside one warehouse.

The stage does not create an alternative stock table or ledger. It must reuse the existing WMS inventory posting boundary. All on-hand changes remain represented by inventory_balances and inventory_ledger.

Out of scope: forecasting, demand planning, purchase-order creation, supplier procurement, financial valuation/accounting, advanced slotting, automated replenishment optimization, reporting aggregates and cross-workflow payload-hash idempotency. Those require later stages or a separately approved design.

## 2. Existing schema authority — reuse V1

The frozen V1 schema already contains the principal tables for this stage:

| Capability | Existing tables |
|---|---|
| Count header and location scope | stock_counts, stock_count_scopes |
| Counted inventory buckets and count history | stock_count_lines, stock_count_results |
| Count-to-adjustment traceability | stock_count_adjustment_links |
| Adjustment document and allocation | inventory_adjustments, inventory_adjustment_lines, inventory_adjustment_allocations, inventory_adjustment_line_serials |
| Replenishment policy and execution | replenishment_rules, replenishment_tasks, replenishment_task_lines, replenishment_task_line_serials, replenishment_confirmations |
| Sole stock projection and movement history | inventory_balances, inventory_ledger, inventory_serials, inventory_lots |

V1 defines count types FULL_COUNT, CYCLE_COUNT, SPOT_COUNT; count modes BLIND, OPEN; the count, line and scope lifecycle states; the adjustment document lifecycle and reason types; and MIN_MAX / MANUAL replenishment rules. Stage 10 must use those allowlists and columns as written.

**V1–V22 are immutable.** Do not edit an existing migration, regenerate the baseline, or create duplicate count/adjustment/replenishment tables. Any required schema extension must be an additive V23+ migration and must identify the exact V1 gap it closes.

### 2.1 Proven schema gap: serialized count observations

stock_count_results stores a counted quantity and counter but does not store which serial identities were physically observed. A quantity alone cannot safely reconcile serialized inventory.

V21 adds the tenant-scoped child table stock_count_result_serials to record serial identities scanned for a specific count result/round. Its composite foreign keys bind each observation to the matching result, line, item, warehouse, tenant and inventory_serials row. A unique key prevents a serial from being scanned twice in one result round, and supporting indexes cover count-line and serial lookup. V21 also adds the composite unique key on stock_count_results required by that foreign key. It records observed identities; the service derives expected serial identities from the frozen location bucket and compares the observed set against them.

Do not create a second serial master. A serial unknown to the tenant/item catalogue must be registered through the existing controlled inventory serial workflow before it can be included in a count.

Later additive migrations should be limited to reviewed permission seeds, audit-event allowlist expansion, and database constraints proven necessary during implementation. Do not add a generic idempotency registry here; Stage 11 owns cross-workflow idempotency.

## 3. Tenant, authorization and audit invariants

- Tenant context comes only from the authenticated WMS principal and persisted active membership. Never trust a client tenant ID or actor ID.
- Every stock count, count line/result, adjustment, replenishment rule/task/confirmation query is tenant-scoped.
- Warehouse, location, item, lot and serial references must belong to the same tenant and matching warehouse/item scope.
- Use opaque public IDs in external APIs. Do not expose internal numeric primary keys.
- Every state transition checks the persisted permission for that exact action. Owners receive only the explicitly listed Stage 10 allowlist through migration; custom-role delegation remains explicit.
- Every successful mutation writes a tenant audit event in the same transaction as its document/stock changes. Audit failure rolls the transaction back.
- Use BigDecimal and DECIMAL(19,4). Reject invalid scale/precision; never use float/double or silent rounding.
- Document numbers and task numbers are tenant-unique. Convert uniqueness conflicts and invalid concurrent state transitions into safe HTTP 409 responses.
- Each workflow command uses a caller-generated UUID where the API contract requires one. Existing unique keys and state transitions prevent duplicate effects, but Stage 10 must not claim general payload-hash replay protection. That remains Stage 11.

Proposed permissions:

- Stock count: WMS_STOCK_COUNT_READ, WMS_STOCK_COUNT_CREATE, WMS_STOCK_COUNT_PLAN, WMS_STOCK_COUNT_START, WMS_STOCK_COUNT_RECORD, WMS_STOCK_COUNT_SUBMIT, WMS_STOCK_COUNT_REVIEW, WMS_STOCK_COUNT_APPROVE, WMS_STOCK_COUNT_CANCEL.
- Inventory adjustment: WMS_INVENTORY_ADJUSTMENT_READ, WMS_INVENTORY_ADJUSTMENT_CREATE, WMS_INVENTORY_ADJUSTMENT_SUBMIT, WMS_INVENTORY_ADJUSTMENT_APPROVE, WMS_INVENTORY_ADJUSTMENT_REJECT, WMS_INVENTORY_ADJUSTMENT_POST.
- Replenishment: WMS_REPLENISHMENT_READ, WMS_REPLENISHMENT_RULE_MANAGE, WMS_REPLENISHMENT_TASK_CREATE, WMS_REPLENISHMENT_TASK_RELEASE, WMS_REPLENISHMENT_TASK_ASSIGN, WMS_REPLENISHMENT_TASK_START, WMS_REPLENISHMENT_TASK_CONFIRM, WMS_REPLENISHMENT_TASK_CANCEL, WMS_REPLENISHMENT_CONFIRMATION_REVERSE.

Inventory adjustment posting also requires the existing WMS_INVENTORY_POST permission enforced by WmsInventoryPostingService. Replenishment confirmation likewise requires its workflow permission plus the inventory-post permission.

Proposed audit event types: WMS_STOCK_COUNT_UPDATED, WMS_INVENTORY_ADJUSTMENT_UPDATED, WMS_REPLENISHMENT_RULE_UPDATED, WMS_REPLENISHMENT_TASK_UPDATED. Preserve every V8–V19 audit allowlist entry when expanding the CHECK constraint.

## 4. Stock count lifecycle

### 4.1 Create, plan and snapshot

A stock count belongs to one warehouse and contains one or more location scopes. A location may appear only once in the same count. Only active tenant-owned warehouses and locations can be scoped.

FULL_COUNT, CYCLE_COUNT and SPOT_COUNT describe the business purpose. BLIND means counter-facing API responses must not disclose expected_qty; OPEN may disclose it only to a membership with WMS_STOCK_COUNT_RECORD or reviewer/approver permission. A read-only membership must not receive expected_qty even for an OPEN count. Hiding the quantity only in the UI is insufficient—the backend response must omit it for unauthorized actors.

Planning validates the warehouse and location scopes but does not take the inventory snapshot. Starting the count acquires the warehouse and all scoped location locks, locks the relevant item/lot rows and inventory balance buckets, then creates count lines for those buckets and activates freeze_stock in the same transaction. expected_qty is the snapshot of inventory_balances.on_hand_qty at that start boundary, never available_qty; reserved stock is physically present and remains part of the count. During an active frozen count, an authorized counter may add an explicit zero-expected line for a scoped location and selected active stock item/lot/status so that unexpected physical stock can be recorded. The bucket must not already exist in the snapshot. The unique bucket key in V1 prevents duplicate lines.

Once the count starts, its expected quantities and scope are immutable. Re-planning or editing the scope after start is prohibited.

### 4.2 Freeze semantics

For the first Stage 10 release, stock counts that start must use freeze_stock = true. A non-frozen count is deferred until ledger-based point-in-time reconciliation is designed and tested.

While a frozen count is IN_PROGRESS, SUBMITTED, UNDER_REVIEW or APPROVED, every on-hand posting that touches a scoped location as source or destination is rejected. This covers receipt, putaway, picking, shipping, inter-warehouse transfer receipt/dispatch, adjustment and replenishment paths because those paths must use the inventory posting boundary. Reservations that change only reserved_qty do not alter physical on-hand quantity; count snapshots and comparisons use on-hand quantity.

The freeze check belongs inside WmsInventoryPostingService, after the transaction has locked the same warehouse/location rows used by count start. Count start must take those locks before writing the frozen state and snapshot. This shared lock is required to prevent a race in which a posting slips between snapshot creation and freeze activation.

The only permitted posting while a frozen count is approved is the narrowly typed posting for the inventory adjustment explicitly linked to that same count. The generic public inventory post operation must never accept a caller-provided freeze bypass. The posting service must verify the count is approved, the adjustment is linked to it, and the posting reference is that exact adjustment.

A count with no variance can be completed without an adjustment and unfreezes its scopes in the same transaction. A count with variance remains frozen until the linked adjustment is approved and posted. Cancellation is permitted only before submission; cancelling an in-progress count clears the freeze atomically. A submitted or approved count must be resolved through review/recount or its adjustment path, not silently cancelled.

### 4.3 Record rounds, recount and review

Each stock_count_line represents one item/location/lot/stock-status bucket. A result round is append-only and unique per line/round under the V1 unique key. A repeated round number is a conflict, not an overwrite.

For serialized items, the result quantity must equal the number of distinct serial identities recorded for that round. Every observed serial must belong to the tenant and item, and its lot must match the count line. A serial already assigned to another active location cannot be silently counted twice; it must be resolved through an authorized movement first. A known LOST serial found physically may be recorded and later restored through the adjustment workflow. Missing expected serials are derived from the frozen snapshot, not supplied as a client assertion.

A discrepancy on the initial round sets the line to RECOUNT_REQUIRED. A recount is a new result round and must be performed by a different active membership from the previous counter. A counter cannot edit or erase prior rounds. If the recount confirms the same quantity and, for serial-tracked items, the same serial identity set as the immediately preceding round, the line becomes COUNTED even when the confirmed value differs from the system snapshot; that stable variance is then eligible for review. If the recount differs, another recount is required from a different membership. The latest stable result is the candidate result for review; voiding a result requires an explicit privileged action with an audit reason.

After all lines have a valid final result, the stock count is submitted (IN_PROGRESS → SUBMITTED). A reviewer reviews each line independently; the first line review moves the header to UNDER_REVIEW and each line records reviewed_by_membership_id and reviewed_at. The header records the completing reviewer only when all lines have been reviewed. V22 adds these per-line review fields and a tenant-scoped membership foreign key. Approval is a separate command and requires every line to be REVIEWED. The count creator, every final-result recorder and every line reviewer are barred from approving the same count. The approver and decision timestamp are recorded on the header. If the approved count has any quantity or serial-identity variance, it remains APPROVED and frozen for the linked adjustment workflow; if no variance exists, approval completes the count and unfreezes all scopes atomically.

Count header lifecycle uses the existing V1 values: DRAFT → PLANNED → IN_PROGRESS → SUBMITTED → UNDER_REVIEW → APPROVED → COMPLETED, with CANCELLED only on allowed pre-submission paths. Line and scope states must use the exact V1 CHECK allowlists.

## 5. Inventory adjustment lifecycle

Adjustments may be generated from an approved physical count or created as a separately authorized reason-coded correction. Supported adjustment types are the V1 allowlist: PHYSICAL_COUNT, DAMAGE, LOSS, FOUND, EXPIRY, CORRECTION, OTHER.

An adjustment document groups lines by item; each allocation represents one warehouse/location/item/lot/stock-status bucket. system_qty, counted_qty and adjustment_qty must satisfy the V1 arithmetic invariant (adjustment_qty = counted_qty - system_qty). A count-generated adjustment copies the approved expected and final counted values and creates a stock_count_adjustment_links traceability row.

Before an adjustment is approved or posted, the service locks and re-reads the affected balance buckets. The stored system_qty must still match the current on-hand quantity. A stale manual adjustment is rejected and must be refreshed/recreated; do not post a delta calculated from an obsolete baseline. A frozen count adjustment can use the exact approved-count exception described in §4.2.

Approval and posting are separate, explicit transitions. The adjustment creator cannot approve their own document. Rejection requires a reason and is audited. Only an APPROVED adjustment can be posted. Posting, allocation/line/header status changes, count completion/unfreeze (for count-linked adjustments) and audit insertion are one transaction.

For each non-zero allocation:
- Positive adjustment_qty posts one ADJUSTMENT_IN movement.
- Negative adjustment_qty posts one ADJUSTMENT_OUT movement.
- Zero variance produces no ledger entry.
- Every movement calls WmsInventoryPostingService; no controller or adjustment repository writes balance, ledger or serial tables directly.

The generic `POST /inventory/postings` endpoint must reject `ADJUSTMENT_IN` and `ADJUSTMENT_OUT`. The adjustment application service uses a dedicated typed posting entry that verifies the approved adjustment, its exact stock-count link, the still-approved/frozen count, the adjustment line, exact allocation, and each scoped location. It also compares the stored system quantity with the locked balance baseline inside the posting boundary. Ledger entries carry `reference_type = INVENTORY_ADJUSTMENT`, the adjustment ID and adjustment-line ID. No caller-controlled freeze bypass is exposed.

Serial adjustments use exact serial identities. The current posting boundary supports ADJUSTMENT_OUT from AVAILABLE to LOST and ADJUSTMENT_IN to restore a known LOST serial into AVAILABLE stock. Stage 10 must extend these operations only for stock statuses that have an explicit serial lifecycle mapping, with integration tests proving the matching status transition. V1 has no EXPIRED or BLOCKED serial lifecycle state, so a variance in those serialized buckets may be recorded and reviewed but must not be silently posted as an adjustment. A status reclassification between AVAILABLE, QUARANTINED and DAMAGED must use an explicit STATUS_CHANGE movement rather than being disguised as a quantity correction. Do not create a new serial identity to mask a count discrepancy. Lot identity and stock status must match the allocation; all quantity and serial counts must reconcile before posting.

## 6. Replenishment rule and task lifecycle

A replenishment rule belongs to one tenant, warehouse, item, source location and destination location. Source and destination must be distinct active locations in the same warehouse. V1 permits MIN_MAX and MANUAL rule types; Stage 10 does not introduce cross-warehouse replenishment because inter-warehouse movement belongs to Stage 9 transfer.

For a MIN_MAX rule, the destination minimum and target are evaluated from destination on_hand_qty for the item. The candidate quantity is the amount needed to reach replenish_to_qty, capped by maximum_replenishment_qty when present and by currently available source quantity. Only AVAILABLE source stock can be moved. Do not include reserved_qty in available stock; use the generated available_qty for source allocation. If source stock is insufficient, the task may be planned for the safely available quantity and completed partially; never create a negative balance or promise unavailable stock.

Planning must not create duplicate open tasks for the same active rule. Check for an existing task in a non-terminal state under a row lock before creating another. A planned task is not a stock reservation. Available quantity is revalidated under the inventory posting locks at confirmation time; stale or competing task plans may be partially confirmed or rejected safely. Stage 11 may introduce stronger reservation and idempotency behavior after command requirements are consolidated.

For lot-tracked stock, each task line carries one explicit lot and a quantity from that lot's source bucket. For serial-tracked stock, the confirmation must contain an exact list of serial identities whose count equals the confirmed whole-unit quantity; the posting service validates their current source location and state while holding the serial locks. A serial may not be moved twice by concurrent tasks.

A replenishment task moves stock from source to destination using a paired TRANSFER posting with equal and opposite legs, same item/lot/stock status and same warehouse. It must not use ADJUSTMENT_IN/OUT, ISSUE, or the inter-warehouse TRANSFER_DISPATCH / TRANSFER_RECEIPT operations. Task and line confirmation quantities, serial rows, immutable ledger entries, balances and audit event commit atomically.

Task header/line states must use the V1 CHECK allowlists. A task can be partially completed when only part of the plan has been moved. replenishment_confirmations records each MOVE; a reversal uses a new REVERSAL confirmation linked to the original confirmation and posts the exact opposite location transfer through the same posting boundary. A confirmation may be reversed at most once. The implementation must enforce that rule under lock and with a database uniqueness constraint if the existing V1 keys do not guarantee it.

## 7. Proposed HTTP API surface

Base path: /api/wms/v1/tenants/current.

Stock counts:
- GET /stock-counts, POST /stock-counts, GET /stock-counts/{countPublicId}.
- POST /stock-counts/{countPublicId}/plan, /start, /submit, /approve, /cancel.
- POST /stock-counts/{countPublicId}/lines/{linePublicId}/review reviews one stable count line.
- POST /stock-counts/{countPublicId}/lines to add a scoped zero-expected bucket for unexpected stock; POST /stock-counts/{countPublicId}/lines/{linePublicId}/results to record a new round; result command includes counted quantity, optional scanned serial public IDs and notes.
- Recount uses the same results operation with the next count round; the server assigns the next round and validates the prior actor. Client cannot choose or overwrite an old round.

Inventory adjustments:
- GET /inventory-adjustments, POST /inventory-adjustments, GET /inventory-adjustments/{adjustmentPublicId}.
- POST /stock-counts/{countPublicId}/inventory-adjustments to generate the adjustment from approved count differences.
- POST /inventory-adjustments/{adjustmentPublicId}/submit, /approve, /reject, /post.

Replenishment:
- GET /replenishment-rules, create/update/deactivate rule operations.
- POST /replenishment-rules/{rulePublicId}/plan to evaluate and create an eligible task.
- GET /replenishment-tasks, POST /replenishment-tasks for manual tasks, GET /replenishment-tasks/{taskPublicId}.
- POST /replenishment-tasks/{taskPublicId}/release, /assign, /start, /confirm, /cancel.
- POST /replenishment-confirmations/{confirmationPublicId}/reverse for an authorized reversal.

List endpoints are tenant-scoped, bounded and stably ordered. Blind-count actor responses must not contain expected quantities. Every command validates active membership, explicit permission, document state, tenant ownership, quantity precision, line/serial reconciliation and concurrency version where applicable. Return safe not-found for cross-tenant documents and HTTP 409 for duplicate command keys, stale versions, unavailable stock and invalid state transitions.

## 8. Transaction, concurrency and integrity requirements

- All stock on-hand changes use WmsInventoryPostingService; all reservations continue to use the existing reservation boundary.
- Keep the established WMS lock discipline: tenant serialization first, then affected warehouse/location/item/lot/serial/balance rows in deterministic order. Stock-count start and posting must contend on the same location locks.
- Lock workflow headers/lines deterministically and use version/status predicates for transitions. Do not perform a read-check-write sequence without row locking or a guarded update.
- Validate all movement legs and resulting balances before any ledger or balance mutation. A multi-line posting either commits fully or not at all.
- Freeze checks include both source and destination legs. A transfer into a frozen scope is blocked just like a transfer out.
- Count, adjustment, replenishment confirmation and audit writes share one transaction. Any late validation, serial mismatch, duplicate key or audit failure leaves no partial inventory/document effects.
- Deadlock retry and broad cross-workflow payload replay are not implemented by ad hoc loops in Stage 10; they are covered by Stage 11 after the workflows are integrated.

## 9. Required disposable MySQL 8.4 integration tests

1. Fresh V1→V21 migration and migration upgrade path; V1–V20 checksums unchanged.
2. Tenant A cannot list, read, mutate, count or post adjustments for Tenant B documents or references.
3. Blind count API never exposes expected quantity to the counter; open count exposes it only with permission.
4. Scope duplication, line-bucket duplication, invalid item/lot/status and non-active location are rejected.
5. Snapshot captures on-hand (not available) for each item/lot/status bucket; zero-expected line is supported.
6. Count start races with receipt/putaway/picking/ship/transfer posting: the location lock serializes the start; no posting slips through after freeze.
7. Posting to either source or destination of a frozen scope is rejected with no balance, ledger or workflow state change.
8. Freeze is released atomically on pre-submission cancellation, zero-variance completion or successful count-linked adjustment posting; generic inventory posting cannot bypass it.
9. Count result round uniqueness, blind/open behavior, quantity precision, recount requirement and different-counter rule.
10. Serial scan duplicate detection, exact serial quantity reconciliation, wrong tenant/item/lot rejection, missing serial derivation and LOST serial found handling.
11. Review/approval permissions, four-eyes separation, invalid status transitions and audit rollback.
12. Count-generated adjustment links and exact variance arithmetic for positive, negative and zero differences.
13. Stale manual adjustment is rejected; adjustment creator cannot approve; rejected adjustment creates no stock movement.
14. ADJUSTMENT_IN/OUT uses correct serial lifecycle and immutable ledger; multi-allocation failure rolls back all balances, ledger and document states.
15. MIN_MAX rule threshold/target/cap arithmetic, inactive rules, insufficient source stock and duplicate open-task prevention.
16. Replenishment only moves within the same warehouse, preserves item/lot/status, validates exact serials and writes paired TRANSFER legs.
17. Concurrent replenishment confirmations against insufficient source stock cannot make on-hand or available quantity negative; serial cannot move twice.
18. Replenishment partial completion, duplicate request IDs and one-time confirmation reversal.
19. Explicit permission catalogue/owner grants, custom-role authorization, audit event allowlist and no cross-tenant reads/writes.
20. Full Stage 8–10 integration suite runs only against disposable MySQL 8.4 Testcontainers, never a user/local/development/production database.

## 10. Delivery sequence and handoff

Keep implementation in focused pull requests, each with its own CI and targeted MySQL integration evidence:

1. Stage 10 architecture contract (this document).
2. Stock count header, scopes, snapshot and freeze enforcement.
3. Count result rounds, serial observations and recount workflow.
4. Review/approval and count-to-adjustment generation.
5. Inventory adjustment lifecycle and posting.
6. Replenishment rules and planning.
7. Replenishment task execution, serial/lot validation and reversal.
8. Tenant-isolation, permission, concurrency, rollback and end-to-end integration tests.
9. Final review, migration integrity verification and post-merge main CI.

Stage 10 is complete only after every slice is merged and the final post-merge main CI is green. Do not start frontend work until the later backend stabilization, security, integrity, performance and production-readiness gates are satisfied.
