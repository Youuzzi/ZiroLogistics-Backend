# Stage 16.7 — k6 Performance Baseline Plan

**Status: PROPOSED / NOT EXECUTED**  
**Scope:** Safe, repeatable performance smoke baseline for the WMS runtime in a disposable environment.  
**Evidence rule:** This plan is not a benchmark result. WP16.7 remains NOT VERIFIED until the test is implemented, run, and its results and limitations are reviewed.

## 1. Grounded environment constraints

- The repository's `compose.yml` defines a MySQL 8.4 service and backend service.
- Compose exposes the backend as host port `8081` mapped to container port `8080`.
- The database name in Compose is `zirologistics_wms`; Compose uses environment-provided credentials.
- Backend CI uses Java 21 and runs the full Maven regression/package workflow for backend-relevant changes.
- Never point performance testing at a developer-owned database or production endpoint. Use a dedicated disposable Compose project and ephemeral credentials.

## 2. What we must not benchmark yet

Do not claim WMS business-operation performance from a test that only hits an unauthenticated route or returns `403`. Do not disable authentication/authorization merely to make business routes load-testable. Avoid creating warehouse transactions at high rates until fixtures, cleanup, tenant ownership, and idempotency are established.

## 3. Proposed test tiers

### Tier A — Runtime smoke / perimeter latency (low load)
Purpose: Verify the runtime is reachable and record response latency for a protected endpoint's expected denial when running without auth fixtures.

- Small virtual-user count and short duration.
- Expected status code documented per route.
- This measures only perimeter/denial-path overhead; it is **not** business API throughput.

### Tier B — Authenticated read-only API baseline (preferred next)
Purpose: Measure realistic read-only endpoint latency using test-only, valid tenant credentials and seeded disposable data.

Prerequisites:
- A repeatable disposable tenant/warehouse/item fixture.
- A valid test authentication token or supported test login flow.
- Explicitly selected read-only endpoints.
- Teardown and verification that fixture data is removed with the ephemeral environment.

Record:
- p50, p90, p95, p99 latency.
- Requests/second, total requests, failures, HTTP status distribution.
- CPU/memory and startup context where practical.
- Environment details (runner/host, app commit, DB image, test duration).

### Tier C — Business workflow load (deferred)
Only after Tier B and workflow fixtures are stable. Avoid high-rate inventory mutations until request idempotency, transaction boundaries, data reset, and contention scenarios are specified. Use separate scenarios for receiving, picking, reservation/posting, and reporting rather than mixing them into one opaque load test.

## 4. Initial guardrails

- Disposable Compose project name unique per run.
- Unique ephemeral DB credentials; never committed.
- Conservative initial load (small number of VUs, short duration); ramp gradually only after smoke checks.
- Fixed request timeout and explicit expected-status assertions.
- No production URLs or developer databases.
- No secrets in logs or artifacts.
- Always collect logs and tear down containers and volumes, including on failure.
- Do not add arbitrary performance thresholds until an initial repeatable baseline is collected and reviewed. Thresholds are proposals, not quality claims.

## 5. Proposed k6 output and artifact

Store the k6 script under a dedicated QA directory, separate from application code. The workflow exports `k6-summary.json` and publishes the summary plus bounded Compose logs as a GitHub Actions artifact with short retention. A baseline report must state commit SHA, test tier, endpoint/scenario, environment, duration/VUs, observed metrics, failures, and caveats.

## 6. Completion criteria for WP16.7

- [ ] Script and workflow reviewed for safe endpoint selection and load limits.
- [ ] Environment is disposable and isolated.
- [ ] Test executes successfully and captures reproducible metrics (`k6-summary.json` artifact).
- [ ] Results are reviewed; limitations and bottlenecks are documented.
- [ ] No production-readiness or capacity claim is made from a single baseline.

## 7. Next implementation decision

Implement Tier B only after confirming a safe repeatable test-tenant/token fixture can be created in the disposable environment. If the required auth fixture cannot be established safely, stop at Tier A and label the result strictly as perimeter smoke performance, not API/business performance.
