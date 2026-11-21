# Stage 16 Parallel QA Wave Matrix

**Purpose:** Track independently testable QA work on isolated branches/PRs and define integration evidence.
**Status:** Working matrix; this is not Stage 16 closure evidence by itself.

## PR wave currently opened

| PR | Focus | Evidence intended | State |
|---|---|---|---|
| #206 | Warehouse tenant isolation | Foreign warehouse public ID is tenant-scoped and not resolved outside the caller tenant | Checks verified green |
| #207 | Inventory quantity boundaries | DECIMAL(19,4) max, overflow, null/zero/negative, rounding-required, trailing-zero normalization | Checks pending at matrix creation |
| #208 | Outbound state safety | Rejected partial release preserves DRAFT, reservation, available quantity and does not write release audit event | Checks pending at matrix creation |
| #209 | CORS policy boundaries | Origins are normalized/deduplicated; blank origin config grants no origins | Checks pending at matrix creation |

All PRs are test-focused and target main independently. They are not merged. Their checks must be revalidated at integration time.

## Stage 16.6 — Inventory integrity

Existing integration coverage includes:
- simultaneous reservations cannot exceed available inventory;
- issue racing reservation cannot oversell;
- duplicate inventory request IDs do not double post;
- same request ID with different payload is rejected;
- failed request can be retried after corrected preconditions;
- balance/ledger paired writes rollback on conflict or audit failure;
- serial receipt/issue/return state remains atomic;
- location weight capacity and concurrent receipt capacity.

New focused quantity boundary PR #207 complements the existing integration tests. Remaining review should verify ledger/balance invariants across affected workflows and inspect any test failures rather than adjust assertions blindly.

## Stage 16.7 — Workflow and transaction safety

Existing evidence includes outbound partial allocation/release requirements, over-allocation rollback, serial allocation controls, inventory posting rollback/deadlock/timeout classifications, and replenishment concurrent confirmation/reversal/idempotency.

PR #208 adds state-preservation evidence for rejected partial release. Additional gaps should be identified from test inventory and workflow source, not duplicated without justification.

## Stage 16.8 — Runtime security

Existing coverage includes HTTP authentication boundaries across WMS endpoints, invalid bearer-token rejection, permission revocation/token conversion, CORS integration boundaries, and security configuration tests. PR #209 covers origin normalization and empty-origin deny-by-default.

Runtime DAST/authenticated scanning remains a separate evidence requirement. Configuration/unit tests do not substitute for deployed/runtime scanning.

## Stage 16.9 — Performance and concurrency

Existing MySQL concurrency scenarios include inventory reservation, issue-versus-reservation, serial transfer races, replenishment confirmations/reversal, and outbound release concurrency (PR #204).

Performance closure still requires a measured runtime load test with clearly defined authenticated workload, target environment, duration, latency/error thresholds, resource observations and repeatability. Perimeter smoke alone is not a throughput benchmark.

## Stage 16.10 — Regression and final audit

After sequential integration:
1. Re-run required full CI on the integrated main commit.
2. Re-run security scans and review findings.
3. Confirm all PR checks and source changes are included in main.
4. Review failures as production logic, test/assertion, fixture, or infrastructure issues.
5. Produce an evidence report with tested scope, findings, fixes, residual risks, and explicit readiness decision.

## Integration policy

- Keep PRs isolated and small.
- Never merge solely because checks are green.
- Merge in reviewed dependency order; re-run full CI after integration.
- No Stage 16 READY declaration until the runtime security, authenticated performance, tenant/inventory/workflow evidence, and final regression gates are satisfied.
