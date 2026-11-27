# WMS Transaction and Concurrency Integrity Architecture

**Status:** Completed — WP1–WP8 merged; final Main CI #503 succeeded on `8cf37a7558a07481d284d91b2b137f28ed61d8cd`.
**Runtime:** WMS-only modular monolith.
**Schema authority:** Frozen WMS V1 plus immutable additive migrations V2–V25; future schema changes are additive V26+.
**Verification:** Disposable MySQL 8.4 Testcontainers only.
**Scope:** Transaction boundaries, concurrent inventory operations, lock ordering, idempotency and safe retry behavior across implemented WMS workflows.

## 1. Objective and boundaries

Stage 11 hardens the transaction and concurrency guarantees of the workflows delivered in Stages 8–10 and the earlier warehouse-operation stages. The objective is to prove that simultaneous valid requests cannot create negative stock, duplicate movement effects, inconsistent document states, lost updates, double reversals, or partial commits.

This stage is an integrity and evidence stage, not a feature-expansion stage. It must first inspect the existing implementation and schema, reproduce risks with deterministic MySQL integration tests, and only then make the smallest justified change.

The stage covers:

1. Inventory posting and reservation races.
2. Replenishment confirmations, reversals and competing task operations.
3. Stock-count freeze/snapshot races against movements and reservations.
4. Adjustment approval/posting races and stale-baseline protection.
5. Inter-warehouse transfer dispatch/receipt and serial-state concurrency.
6. Idempotency key scope, duplicate behavior, payload conflicts and safe client retries.
7. Lock ordering, deadlock/lock-timeout behavior, transaction atomicity and recovery evidence.

Out of scope: frontend work, performance optimization without measured evidence, distributed transactions, microservices, event sourcing, speculative schema redesign, and changes to user/local/production databases.

## 2. Non-negotiable invariants

### 2.1 Inventory quantities

- For every inventory bucket, on-hand quantity must never become negative.
- Available quantity must never become negative and remains on-hand minus reserved under the current schema semantics.
- A reservation cannot exceed the currently available quantity.
- Concurrent issue, transfer, adjustment or replenishment confirmation operations must not oversell a shared source bucket.
- Paired movements must commit all legs or none.
- A successful movement must have matching balance effects and immutable ledger evidence.
- Serial identity must never be present in two locations or be moved twice by concurrent commands.
- Lot, item, warehouse, location, stock status and tenant identity must remain consistent across every movement leg.

### 2.2 Workflow and document state

- A task, count, adjustment, receipt or transfer transition must be committed atomically with the business effects it represents.
- Concurrent transitions from the same prior state must produce one valid winner or a deterministic conflict; they must not silently overwrite one another.
- A replenishment line's confirmed quantity must not exceed its planned quantity, including when several confirmations race.
- A confirmation reversal may be applied at most once.
- A count freeze and its snapshot must form one consistent boundary: no posting may slip between snapshot capture and freeze activation, and no posting may pass through an active freeze.
- A stale adjustment baseline must be rejected at approval and again at posting.
- Audit evidence for a successful business mutation must commit in the same transaction; audit failure must roll back the mutation.

### 2.3 Tenant and authorization

- Tenant context comes only from the authenticated principal and persisted active membership.
- Every lock, read, conditional update and uniqueness check must include the tenant scope where the schema requires it.
- Effective permissions are resolved server-side. A client-supplied tenant, membership, actor or role identifier is never authorization evidence.
- Authorization must not race with membership or role changes in a way that permits a revoked actor to commit a protected mutation after the revocation boundary.
- Cross-tenant public-ID spoofing must fail without revealing another tenant's data.

## 3. Existing implementation baseline to verify

The current code contains a shared inventory posting boundary and uses tenant-scoped row locks, conditional updates, database uniqueness and transaction annotations. Existing integration evidence includes:

- Competing inventory issues serialized by a tenant lock, with no negative stock.
- Duplicate inventory posting request IDs rejected without double posting.
- Paired balance/ledger changes rolled back when a later entry conflicts.
- Serial receipt, issue and return state transitions tested atomically.
- Stock count freeze blocks posting and reservation changes; overlapping active counts are rejected.
- Replenishment partial confirmation, over-quantity rejection, exact serial movement, one-time reversal and rollback-sensitive task behavior.
- Manual task creation aggregates demand across different destinations sharing one source bucket.
- Adjustment baseline revalidation, reserved-stock protection and multi-allocation rollback.

These tests are evidence for the scenarios they execute; they do not establish that every concurrent interleaving is safe. In particular, the existing inventory issue concurrency test does not prove that two replenishment confirmations racing against the same source bucket are safe.

Before implementation, record the current behavior and exact lock sequence of each affected service. Do not infer database isolation or lock behavior from annotations alone; verify it against the actual MySQL 8.4 runtime used by Testcontainers.

## 4. Locking and transaction design rules

### 4.1 Lock inventory and workflow aggregates deliberately

For each operation, identify the aggregate/resource whose invariant is protected: tenant authorization state, warehouse/location scope, inventory balance bucket, serial identity, workflow header, workflow line, reservation, or transfer document.

The current implementation acquires a tenant row lock in several WMS mutation paths. This provides a broad serialization boundary, but may also serialize unrelated work for the same tenant. Stage 11 must measure and document the behavior before deciding whether to retain, narrow or replace it. Do not remove the tenant lock merely to increase concurrency; authorization consistency and cross-workflow invariants must remain protected.

If resource-level locking is introduced, it must preserve a documented common lock order and prove safety across every workflow that touches the same resources.

### 4.2 Lock order and shared-resource ordering

Do not assume one universal order across workflow aggregates and inventory resources before mapping the existing call paths. Several workflows intentionally lock their own document/header/line before invoking the inventory posting boundary. Stage 11 must inspect those call paths and identify whether any competing workflow acquires the same resources in the opposite order.

Within the inventory posting boundary, the current intended resource order is:

1. Warehouse rows in ascending internal ID.
2. Location rows in ascending internal ID.
3. Item rows in ascending internal ID.
4. Lot rows in ascending internal ID.
5. Serial rows in ascending internal ID.
6. Inventory balance buckets in deterministic key order.

The tenant authorization boundary is acquired before mutation/permission resolution where the current service contract requires it. Workflow aggregate locks (task, count, adjustment, transfer, receipt and their lines) must be documented as a separate layer with the exact order in which each workflow acquires them relative to inventory resources.

This is an audit baseline, not permission to reorder locks mechanically. Stage 11 must map actual call paths, identify inversions, and establish compatible ordering for every shared resource. Any deviation must be documented with a concrete reason and a deterministic concurrency/deadlock test.

Never rely on unordered HashMap iteration or request-line order to determine database lock acquisition order. Sort lock keys deterministically before locking multiple rows.

### 4.3 Transaction boundaries

- A logical stock movement, all balance changes, all ledger rows, serial transitions and audit event must share one transaction.
- A replenishment confirmation/reversal and its confirmation evidence, task-line quantities/status, serial evidence and audit event must share one transaction with the posting service.
- An adjustment's multi-allocation posting and final document/count state must commit or roll back as one unit.
- A stock count start must atomically lock its scope, capture its snapshot and activate the freeze.
- Do not catch and suppress a persistence exception inside a transaction if doing so could allow a partial commit.
- Spring service calls must join the intended transaction; verify proxy boundaries and propagation rather than assuming that a method annotation alone proves atomicity.

### 4.4 Deadlocks and lock timeouts

- Prefer deterministic lock ordering and short transactions over broad automatic retries.
- Record the actual MySQL error classification for deadlocks, lock wait timeouts and uniqueness conflicts.
- A transaction rolled back by MySQL may be retried only when the complete operation is known to be safe and uses stable idempotency semantics.
- Any retry must be bounded, observable and limited to a documented transient failure class.
- Do not retry validation, authorization, stale-version, stale-baseline or payload-conflict errors.
- Never retry an operation after an external side effect unless that side effect has its own idempotency/compensation contract.

## 5. Idempotency and request identity

Stage 11 must inventory every mutating API and record whether it has a request ID, unique database constraint, deterministic duplicate response, and payload-conflict behavior.

For each command, explicitly define:

- Key scope: tenant, operation type and/or resource.
- Whether the same key with the same payload returns the original result or a safe duplicate/conflict response.
- Whether the same key with a different payload is rejected.
- Which database constraint is the final duplicate-effect barrier.
- What happens when the server commits but the client times out before receiving the response.
- Whether a retry can safely resume or must reload the resource and issue a new command.

Do not claim exactly-once delivery. The realistic target is at-most-once business effect with deterministic duplicate handling and safe retry/reconciliation. A payload hash or replay-response registry may be introduced only after a documented schema and retention design; it must not be added speculatively.

## 6. Required MySQL concurrency evidence

Tests must use independent transactions/connections and coordinate their start with deterministic barriers/latches. Sleeps alone are not acceptable evidence of a race. Assertions must inspect final balances, reservations, ledger rows, serial state, confirmation rows, document status and audit evidence.

Minimum scenarios:

1. Two simultaneous issues against one bucket where combined demand exceeds available stock: only valid demand may commit; stock must not become negative.
2. Two simultaneous reservations against one bucket: total reserved must not exceed on-hand.
3. Issue versus reservation on the same bucket: no oversell and a deterministic outcome.
4. Two replenishment confirmations from the same source bucket to different destinations: combined confirmed movement must not exceed available stock.
5. Two confirmations against the same replenishment line: planned quantity must not be exceeded and each request must have a deterministic result.
6. Two reversal requests for the same confirmation: exactly one reversal may commit.
7. Two commands moving the same serial: exactly one valid location transition may commit.
8. Stock count start racing with receipt, issue, transfer or reservation: the snapshot/freeze boundary must be consistent and no movement may bypass an active freeze.
9. Adjustment approval/posting racing with another movement on the same bucket: stale baseline must be rejected or the operations serialized safely.
10. Inter-warehouse transfer dispatch/receipt racing with serial reconciliation: serial status and location must remain valid.
11. Duplicate request submitted concurrently: one business effect only, with the documented duplicate response.
12. Failure injected after the first leg/allocation: all balance, ledger, serial, document and audit changes must roll back.
13. Deadlock/lock-timeout behavior: verify transaction rollback, surfaced error classification and bounded retry behavior if implemented.

Where a scenario is not applicable to the current workflow, record the reason and evidence. Do not delete or weaken existing tests to make concurrency tests pass.

## 7. Schema and migration policy

- V1–V25 are immutable. Future schema extensions must be additive V26+ migrations.
- Reuse existing unique keys, foreign keys, indexes and version columns wherever they enforce the required invariant.
- Add a migration only when a concrete concurrency or idempotency gap cannot be closed safely by the existing schema and transaction design.
- Every new unique key must be tenant-aware and match the intended business scope.
- Add indexes for locking predicates only after checking query plans and actual access paths.
- Never use a migration to rewrite historical inventory movements or silently repair user data.
- Validate a fresh V1→latest migration and upgrade paths in disposable MySQL 8.4 Testcontainers.
- Never run Flyway or schema-changing tests against the user's local databases.

## 8. Delivery work packages

Deliver focused PRs with targeted tests and exact commit CI evidence:

1. **Stage 11 Architecture & Baseline Audit** — map transaction boundaries, aggregate locks, lock order, request IDs, unique constraints and current concurrency tests. No behavior change unless required to correct an objectively unsafe contract.
2. **Inventory Posting & Reservation Concurrency** — deterministic tests for issue/reservation races, multi-bucket locking, balance invariants and duplicate posting behavior.
3. **Replenishment Concurrency** — concurrent confirmations, same-line conflicts, shared-source oversell prevention, serial movement and one-time reversal.
4. **Stock Count & Adjustment Races** — snapshot/freeze versus posting/reservation, stale baseline versus concurrent movement, adjustment posting rollback.
5. **Transfer & Serial Concurrency** — dispatch/receipt/reconciliation interleavings and exact serial location/status guarantees.
6. **Idempotency & Retry Semantics** — define and implement only the missing request-key guarantees, duplicate behavior, payload conflict and timeout recovery.
7. **Deadlock & Atomicity Hardening** — lock-order fixes, bounded retry only where justified, failure-injection tests and rollback evidence.
8. **Final Integrity Audit & CI** — run the complete backend suite, review all changed SQL/transaction boundaries, verify migrations and document residual risks.

A work package may be combined with another only when the scope remains reviewable and its evidence is clear. No direct commits to main; every change uses a feature branch and pull request. PR CI must pass before merge, and post-merge main CI must pass on the exact merge commit before the next work package is based on main.

## 9. Definition of Done

Stage 11 is complete only when:

1. Every implemented stock-mutating workflow has a documented transaction boundary and lock order.
2. All minimum applicable concurrency scenarios have deterministic MySQL 8.4 integration evidence.
3. No tested interleaving can create negative on-hand/available/reserved quantities, duplicate movement effects, double reversal or invalid serial placement.
4. Document/task state and inventory effects remain atomic under injected failures.
5. Duplicate request and client-timeout behavior is explicitly documented and tested.
6. Deadlock and lock-timeout behavior is classified; any retry is bounded and demonstrably safe.
7. Tenant isolation and permission checks remain intact under concurrent membership/role changes.
8. Existing test coverage is preserved or expanded; no test is removed or weakened to achieve a green build.
9. V1–V25 checksums remain unchanged; any approved V26+ migration passes fresh and upgrade validation.
10. The full backend CI is green on the exact post-merge main commit.

Evidence labels remain mandatory: VERIFIED, PARTIALLY VERIFIED, INFERRED, UNVERIFIED and PROPOSED. Code inspection alone does not establish runtime concurrency safety.

## 10. Final audit evidence and boundaries

- **VERIFIED:** Stage 11 PRs #108–#118 are integrated; final Main CI #503 passed on the exact Stage 11 merge commit. The final suite reported 308 tests, 0 failures, 0 errors and 2 skipped.
- **VERIFIED:** Deterministic MySQL 8.4 tests cover competing issues/reservations, issue-versus-reservation, replenishment shared-source and same-line races, one-time reversal, serial movement races, count/freeze and adjustment races, duplicate request replay, injected rollback, and MySQL deadlock/lock-timeout classification.
- **PARTIALLY VERIFIED:** The deadlock and lock-timeout classification tests deliberately exercise independent MySQL transactions directly; they establish MySQL rollback/error behavior, not a guarantee that every production workflow is deadlock-free.
- **PARTIALLY VERIFIED:** Lock ordering is explicit inside the inventory posting boundary and tested on selected opposing transfer operations. Workflow aggregate locks remain operation-specific; do not infer one universal global lock order or concurrency safety for untested interleavings.
- **VERIFIED:** No automatic transaction retry was introduced. Deadlock/timeout outcomes must surface to the caller; only commands with documented request identity may be safely replayed using the same request ID.
- These boundaries are residual evidence limitations, not known production defects. Keep them visible in future structural audit and API contract work; do not weaken the existing tests.

## 11. Handoff

Stage 11 follows Stage 10 Inventory Control. Stage 12 Business Workflows & Reporting may begin only after Stage 11 is complete and its final post-merge Main CI is green. Backend structural audit/refactoring remains scheduled after Stage 13 and before frontend implementation. Frontend work remains blocked until all backend stages and production-readiness gates are complete.
