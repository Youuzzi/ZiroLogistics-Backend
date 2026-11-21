# Stage 16 Remaining QA Coverage Gap Register

**Status:** working register; not a readiness verdict
**Baseline:** main at `4141e74bf95ac575d83e5f151806d01f24fc47a5`
**Purpose:** direct subsequent QA waves toward evidence gaps without duplicating tests already present or currently proposed in open PRs.

## Evidence interpretation

- A test file present on `main` is repository evidence that a test scenario exists; it is not proof that all edge cases are covered or CI is green.
- Open PR coverage is tracked separately from main. Until integrated and retested, it is not part of main's verified test baseline.
- Runtime security and authenticated performance work cannot be replaced by unit tests, static scanners, or unauthenticated perimeter smoke tests.
- A gap is a hypothesis to validate by source and test review. It is not automatically a production defect.

## Module coverage observed on main

| Area | Main test evidence | Next review target |
|---|---|---|
| Identity/password reset/email verification/invitations | Unit + MySQL integration | Expired token rejection, replay races, credential mutation invariants |
| Inventory posting/reservations/ledger | Extensive MySQL integration and unit tests | Boundary review; avoid duplicating concurrency/rollback coverage |
| Inbound | MySQL workflow integration | Partial receipt, duplicate/replay, invalid state and tenant reference review |
| Outbound | MySQL integration | Partial allocation/release rejection, concurrent transitions and rollback |
| Putaway | MySQL integration | Cross-tenant, invalid target/location capacity, transition rollback |
| Replenishment | Planning + concurrency MySQL tests | Tenant references, stale plan and duplicate confirmations |
| Stock count | MySQL integration | Freeze scope, reservation interaction, adjustment/rollback and tenant boundary |
| Warehouse/transfer | Unit + HTTP/integration tests | Cross-tenant public IDs and concurrency/state transitions |
| Tenancy/provisioning/profile | MySQL integration + unit/HTTP tests | Provisioning rollback/idempotency, inactive tenant/membership boundaries |
| Security/auth/CORS/throttling | Unit + HTTP + integration tests | Config edges, authentication denial, token claims, runtime checks |
| Reporting | Existing main baseline is limited; tenant/reporting QA PRs are open | Tenant-filtered aggregation, query-level isolation, pagination/filters and malformed input |
| Picking/packing/adjustment/shipping | Dedicated tenant/security test additions exist in open QA PRs, not yet integrated | State transition matrices, duplicate requests, rollback and tenant negative cases |

## Current parallel QA PR coverage (not yet merged)

- PR #198–#205: focused cross-tenant isolation and workflow concurrency/reporting scenarios.
- PR #206: foreign warehouse public-ID read boundary.
- PR #207: inventory decimal quantity boundaries.
- PR #208: partial outbound release rejection preserves state.
- PR #209: CORS origin normalization and deny-by-default boundaries.
- PR #210: parallel wave evidence matrix.
- PR #211: expired password-reset token rejection/immutability (new).
- PR #212: password-change same-password rejection before transaction (new).

All above must be reviewed and CI-confirmed; open PR status is not integrated evidence.

## Remaining waves proposed after current PRs

### Wave A — API security/runtime
1. Authenticated ZAP baseline against a disposable test deployment with safe scan policy.
2. Token lifecycle and HTTP denial matrix: expired, malformed, wrong token purpose, missing membership, disabled identity/tenant, and privilege revocation.
3. Error response review for leaked internal exception messages, stack traces, and sensitive identifiers.

### Wave B — workflow and transaction integrity
1. Build explicit state-transition matrices for picking, packing, shipping, adjustment, stock count and transfer.
2. Duplicate/idempotency requests at the HTTP boundary, not only service method tests.
3. Failure injection at transaction boundaries; assert every balance, ledger, reservation and audit side effect rolls back together.
4. Cross-tenant foreign references for both read and mutation paths.

### Wave C — performance/concurrency
1. Authenticated k6 workload with documented test tenant/warehouse/item data and repeatable seed.
2. Report p50/p95/p99 latency, throughput, error rate, request mix, VUs, duration, and resource observations.
3. Concurrent inventory issue/reserve/transfer/receipt/count operations; assert business invariants in the database.
4. No performance claim from CI duration or the current unauthenticated perimeter smoke test.

### Wave D — regression/closure
1. Review failing CI causes and classify (production defect, test defect, fixture/setup, infrastructure).
2. Resolve validated defects at the correct layer; retain evidence for test expectation changes.
3. Integrate PRs in dependency-safe order and run full backend regression on the combined branch/main.
4. Final Stage 16 readiness report: READY only when every required runtime/security/performance/regression gate has evidence; otherwise publish NOT READY with exact remaining gates.

## Decision rule

Do not close Stage 16 based on PR count, green static checks, or test count alone. Use traceable evidence for each gate and state untested limits plainly.
