# Stage 16.8 — QA Closure Evidence Gates

**Status:** Stage 16 remains OPEN. This document is updated from actual repository PR, CI, and retained scan artifacts reviewed on 2026-11-22. It is not a production-readiness declaration.
**Scope:** Stage 16 Wave A security, tenant-isolation, dependency, DAST, performance and closure evidence.

## Evidence that must exist before closure

| Area | Evidence expected | Current evidence/status | Closure condition |
|---|---|---|---|
| Risk-to-test traceability (WP16.1) | Risk/test matrix | PR #165 merged | Confirm matrix remains current |
| Coverage baseline (WP16.2) | JaCoCo artifact | PR #166 merged; baseline only | Preserve baseline; targeted changes only, no arbitrary coverage chase |
| Dependency scanning (WP16.3) | OSV report and finding disposition | PR #237 CI green; strict OSV scan; SARIF results empty after two documented ignores | Reassess the two ignored Spring advisories; keep scan blocking unaccepted findings |
| Static analysis (WP16.4) | CodeQL results | PR #237 CodeQL SARIF had 2 CSRF alerts; PR #249 CodeQL artifact had 0 results after scoped CSRF fix | Re-run CodeQL on consolidated security changes |
| HTTP security verification (WP16.5) | Authentication/authorization boundary tests | PRs #180–#195, #217–#219, #230, #247, #248 and related tests; CI is green on their recorded heads | Review exact route coverage and ensure fixes are validated on current base |
| DAST baseline (WP16.6) | ZAP baseline artifact and triage | Executed twice successfully; details below. 0 High/Medium/Low; 1 Informational Non-Storable Content. Scan is unauthenticated/perimeter-only | Keep baseline as perimeter evidence; do not interpret as full API coverage |
| Authenticated DAST | Authenticated ZAP artifact and triage | PR #245 run succeeded on its original head; details below. One Low CORP finding was observed and has been addressed in PR #245's updated branch; re-scan is pending | Confirm updated scan clears CORP and has actual protected-route coverage |
| Performance (WP16.7) | k6 result artifact and interpretation | PR #173 is closed unmerged. No authenticated workflow/business-performance benchmark is present on main; no k6 result artifact is verified | Execute a representative authenticated business workflow benchmark and retain report before closure |
| Tenant isolation follow-up | Tenant A/B tests plus no-side-effect assertions | MySQL tests in #179, #182, #183, #197, #198 and #205; focused mock-JDBC tests in #200–#203; warehouse #176. PR #206 test now asserts the exact not-found exception, CI pending. Old #199 was closed as stale; replacement #255 is on current main, CI pending | Ensure reads, writes, workflow references and reporting are covered by real MySQL tenant A/B fixtures; mock-only tests remain supplementary |
| Closure matrix | PR inventory and evidence mapping | PR #214 is stale and has no check runs; its count/status snapshot must not be used as current | Refresh the inventory from current PR states and CI SHAs before final Stage 16 closure |

## DAST baseline evidence (actual runs)

Two successful disposable perimeter ZAP baseline runs were retained from PR #231 branch `qa/stage-16-a5-dast-execution`, commit `950410d31dec915c97e40b82c6c543536d201ba`:

- Run [44903819529](https://github.com/Youuzzi/ZiroLogistics-Backend/actions/runs/44903819529): successful; artifact `wms-zap-baseline`, ID `14707465336`.
- Run [44903814826](https://github.com/Youuzzi/ZiroLogistics-Backend/actions/runs/44903814826): successful; artifact `wms-zap-baseline`, ID `14706474951`.
- Each report: 0 High, 0 Medium, 0 Low, 1 Informational alert (`Non-Storable Content`); the summary reported 9 endpoints, 100% GET and 100% 4xx.

The baseline is unauthenticated and perimeter-only. It is evidence that the disposable runtime was scanned, not that protected API routes or business workflows were tested.

## Authenticated ZAP evidence (actual run)

PR #245 authenticated API scan ran successfully on original head `749014d52c54f128321d664dfb673cbaef7d73aa`:

- Run [44936318748](https://github.com/Youuzzi/ZiroLogistics-Backend/actions/runs/44936318748): job `Authenticated WMS API scan (ZAP)` succeeded.
- Artifact `wms-zap-authenticated`, ID `14719653642`.
- Report: 0 High, 0 Medium, 1 Low (`Cross-Origin-Resource-Policy Header Missing or Invalid`, 3 instances), 2 Informational alerts (30 404 instances and 3 non-storable-content instances).
- Report summary: 32 endpoints, 100% GET, 98% 4xx and 1% 2xx.
- Scan scope is limited to the tenant profile, warehouse list and warehouse detail endpoints; it is not a full controller/workflow scan.

The Low CORP finding was remediated on PR #245's updated branch by setting `Cross-Origin-Resource-Policy: same-origin` in both WMS security chains and adding HTTP assertions. The CSRF exemption was also narrowed to stateless bearer API routes, and CSRF remains enabled outside `/api/wms/**`. The updated CI/DAST rerun is pending; do not call the finding closed until that run completes and the artifact is reviewed.

## Dependency and static-analysis disposition

PR #237's strict OSV workflow completed successfully on its recorded head. Its SARIF artifact `results.sarif` contains 0 results because two Spring Framework advisories are explicitly ignored:

- CVE-2026-47890
- CVE-2026-47884

The PR's source/config reachability review states the current WMS REST runtime has no SSE, view-fragment rendering, `XsltView`, `ViewResolver`, or server-rendered template resources. These advisories are accepted as conditionally unreachable—not fixed dependency vulnerabilities. The exception must be revisited if the trigger paths are introduced. Keep OSV strict/blocking for other unaccepted findings.

CodeQL SARIF from PR #237 showed two `spring-disabled-csrf-protection` findings. PR #249's CodeQL SARIF had 0 results after narrowing the exemption. PR #245 now contains the same CSRF fix and CORP remediation; latest validation is pending.

## Tenant isolation evidence map

- **Real MySQL/Testcontainers:** #179 inventory posting foreign/mixed warehouse-location-item-lot-serial negative cases with balance, ledger, audit unchanged assertions; #182 inbound receiving cross-tenant tests; #183 outbound cross-tenant resource references; #197 putaway read isolation; #198 stock-count/count-line isolation; #205 operational reporting tenant isolation.
- **Concurrency/integrity:** #204 concurrent outbound release transition; #208 partial release failure preserves shipment/reservation/availability/audit state; #207 decimal quantity boundary tests.
- **Focused service/mock tests:** #200 transfer, #201 picking/packing, #202 replenishment, #203 adjustment/shipping, #206 foreign warehouse public ID. These are supplementary, not equivalent to full MySQL/HTTP tenant A/B tests.
- **Warehouse service boundary:** #176 verifies tenant-scoped public-ID reads, foreign parent-location rejection, and no writes on rejection.
- **Inventory source audit:** #177 is source SQL review only. #178 is a test specification only. Neither is runtime evidence.

## Performance evidence

PR #173 is closed unmerged and describes only a low-load perimeter smoke. No verified k6 artifact exists for an authenticated business workflow. This is a Stage 16 closure blocker; do not infer performance readiness from ordinary CI duration or a denied-route smoke test.

## CI and PR hygiene

- The recorded successful checks are attached to individual commit SHAs. A green check on an older head/base is not evidence for a later fix.
- PR #199 was closed as stale; its focused reporting boundary test was ported to #255, which uses current main. The old CI result is not transferred to #255.
- PR #251 was closed as a duplicate of the Testcontainers username correction already included in #234 and #253.
- PR #214 has no check runs and its inventory count is stale.
- PR #206, #234, #245, #249 and #255 have changes requiring fresh validation; inspect latest SHA checks before deciding READY.

## Required closure decision

Stage 16 **NOT READY** until:

1. Latest security remediation CI and authenticated ZAP rerun complete successfully and artifacts are reviewed.
2. No untriaged CodeQL/OSV findings remain; the two conditional Spring advisory exceptions remain explicit and reviewed.
3. Tenant A/B MySQL tests cover representative reads, writes, cross-resource references, reporting and no-side-effect rejection; mock-only coverage is not treated as integration proof.
4. A real authenticated business-workflow k6 benchmark is run, with load profile, request count, failure rate and latency percentiles retained.
5. The PR inventory and merge order are refreshed against current heads/base SHAs, and the main branch is untouched by this audit until explicit merge approval.

Green CI alone is not the Stage 16 closure signal.
