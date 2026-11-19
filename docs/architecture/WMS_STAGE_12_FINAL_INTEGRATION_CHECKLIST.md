# WMS Stage 12 — Final Integration & CI Checklist

**Status:** Prepared; final execution evidence is pending.
**Baseline:** `f1ae5a05b14bf77f90ee92b4390113f8c29f6187`
**Runtime verification:** Java 21, Maven, disposable MySQL 8.4 Testcontainers.
**Schema baseline:** V1–V25 immutable. WP7 reporting proposal adds V26; validate the complete migration chain before integration.

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
8. WP8 — Workflow Audit & Traceability (PR #127).
9. WP9 — Final integration validation (this checklist).

Before each merge:
- confirm the PR CI is successful for the exact current head SHA;
- review the final diff and all unresolved review threads;
- update/rebase against the latest main when required;
- rerun full PR CI after any code, SQL, configuration, dependency, test or workflow change;
- merge only after the preceding WP's post-merge Main CI is green.

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

## 3. Stage 12 final verification

- [ ] All WP PR CI runs pass on exact final PR head SHAs.
- [ ] Every PR has been reviewed; no unresolved review threads remain.
- [ ] PRs merge in WP order, with post-merge Main CI green after each merge.
- [ ] V1–V25 checksums remain unchanged; V26 is additive and Flyway validates from a fresh database.
- [ ] Full Maven regression runs on the final Stage 12 main commit using Java 21 and MySQL 8.4 Testcontainers.
- [ ] Final CI logs show zero failures and zero errors; any skipped tests are understood and unchanged from the established suite.
- [ ] Final Main CI is green on the exact final merge SHA.
- [ ] Documentation and migration registry reflect the final delivered Stage 12 state.

## 4. Evidence discipline

A PR CI pass proves only the PR branch against its tested baseline. A post-merge Main CI pass proves the merged main commit. The final Stage 12 completion claim requires both the per-WP integration sequence and the final full Main CI.

No Stage 12 completion status is asserted by this checklist before those conditions are met.
