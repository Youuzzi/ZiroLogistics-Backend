# WMS Integration and CI Verification Checklist

> **Historical integration checklist:** This checklist records the original Stage 12 integration order and CI evidence. It is retained as delivery history, not as the current migration or release checklist. The current runtime and security/QA checkpoint are maintained in the [Backend Maintenance Handoff](../project/backend-maintenance-handoff-2026-11-27.md) and [Security and QA Baseline](../audits/security-qa-baseline.md).

**Status:** Historical integration checklist; the original integration delivery and recorded Main CI gate completed.
**Baseline:** `f1ae5a05b14bf77f90ee92b4390113f8c29f6187`
**Runtime verification:** Java 21, Maven, disposable MySQL 8.4 Testcontainers.
**Original schema baseline:** V1–V25 unchanged; additive V26 was applied and validated through fresh and upgrade Testcontainers paths. Current runtime migration history is V1–V32.

## 1. Integration sequence

Stage 12 work is prepared on independent branches so each WP can run its own pull-request CI without waiting for earlier WPs. Passing PR CI is necessary but does not prove compatibility with later merged changes.

Merge order remains:
1. WP1 — Workflow Architecture & Baseline Audit (PR #119; already merged).
2. WP2 — Inbound Workflow Integrity (PR #120 and PR #121; #121 Main CI is the inbound baseline gate).
3. WP3 — Outbound Workflow Integrity (PR #122).
4. WP4 — Inventory Lifecycle Integration (PR #123).
5. WP5 — Inter-Warehouse Workflow (PR #124).
6. WP6 — Replenishment & Stock Control Integration (PR #125).
7. WP7 — Operational Reporting (PR #126; additive V26).
8. WP8 — Workflow Audit & Traceability (PR #130; rebased after outbound test integration).
9. WP9 — Final integration validation (this checklist).

Before each merge:
- confirm the PR CI is successful for the exact current head SHA;
- review the final diff and all unresolved review threads;
- update/rebase against the latest main when required;
- rerun full PR CI after any code, SQL, configuration, dependency, test or workflow change;
- complete all per-WP PR CI and compatibility checks first; then merge PRs in WP order without waiting between automatic Main CI runs. The final full Main CI after the last merge is the Stage 12 completion gate.

## 2. Canonical workflow acceptance matrix

| Workflow | Required evidence |
|---|---|
| Inbound → putaway | Accepted/rejected quantity reconciliation; accepted-only receipt posting; posted receipt prerequisite; partial putaway; duplicate confirmation replay; receipt/putaway rollback and tenant scope |
| Outbound → shipped | Allocation/reservation; picking to staging without ISSUE; package/serial reconciliation; sealed-package gate; final ISSUE; tenant scope; actor/request traceability |
| Inventory lifecycle | Receipt and paired transfer ledger reconciliation; count snapshot equals balances; active freeze blocks movements; adjustment revalidates baseline; no direct balance/ledger/serial writes |
| Inter-warehouse | Same-tenant distinct warehouses; dispatch to transit; partial good/damaged/short receipt; serial lifecycle; duplicate dispatch/receipt request protection |
| Replenishment + stock control | Side-effect-free planning; aggregate source demand; partial confirmation; one-time reversal; count freeze and reservations prevent unauthorized movement |
| Operational reporting | WMS runtime route; persisted report permission; tenant-scoped aggregation; available quantity semantics; read-only behavior; no legacy dashboard dependency |
| Audit traceability | Tenant-scoped actor identity; event subject and operation reference; request IDs where supplied; audit and workflow changes share transaction; audit failure rolls back posting |

## 3. Final integration verification

- [x] All delivered WP PR CI runs passed on their final tested head SHAs; superseded/duplicate PRs #127–#129 were not merged.
- [x] Final delivered PRs reviewed; no unresolved inline review threads on PRs #108–#126, #130 and #131.
- [x] WP1–WP7 merged in order (#119–#126); integrated WP8 #130 and WP9 #131 merged after compatibility checks.
- [x] V1–V25 unchanged in the Stage 12 diff; V26 is additive and Flyway fresh/upgrade validation passed in Testcontainers.
- [x] Full Maven regression ran on final Stage 12 main commit using Java 21 and MySQL 8.4 Testcontainers.
- [x] Final Main CI #558 completed successfully; the Java 21 test step passed. No CI failure or error was reported.
- [x] Final full Main CI #558 is green on exact final Stage 12 SHA `893ad6ba82dfc1ae5acc3a5fa471c047fd9e0e5c`.
- [x] Stage 12 architecture and integration checklist record the delivered scope and final CI evidence.

## 4. Evidence discipline

A PR CI pass proves only the PR branch against its tested baseline. A post-merge Main CI pass proves the merged main commit. The final Stage 12 completion claim requires both the per-WP integration sequence and the final full Main CI.

Stage 12 completion is asserted for the exact main commit and CI run recorded above. Reporting status-count aggregation across each individual workflow table remains partially verified by source inspection rather than a separate assertion per table.
