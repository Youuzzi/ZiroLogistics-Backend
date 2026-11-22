# Stage 16.8 — QA Closure Evidence Gates

**Status: Stage 16 remains OPEN / NOT READY.** Updated 2026-11-22 from current PR heads, GitHub Actions runs, and retained artifacts. This is evidence tracking, not a production-readiness declaration.

## 1. Current verified evidence

| Area | Evidence | Current assessment |
|---|---|---|
| Risk traceability | #165 merged | Matrix exists; keep aligned to final coverage |
| Coverage baseline | #166 merged; JaCoCo artifact | Baseline only; no arbitrary coverage target |
| Dependency remediation | #237 | Earlier head passed CI and strict OSV. Current head adds expiry for the two accepted Spring exceptions and fixes CI diff scoping; new CI is running |
| Static analysis | #237 CodeQL SARIF contains 2 CSRF findings; #249 SARIF contains 0 after scoped CSRF exemption | #245 includes the scoped fix; its CodeQL check passed, but the latest branch SARIF artifact was not available for direct result-count inspection |
| HTTP/security tests | #180–#195, #217–#219, #230, #247, #248 and related tests | Good boundary coverage, but checks are on isolated PR heads and not yet integrated |
| Tenant isolation | MySQL A/B integration tests #179, #182, #183, #197, #198, #205, #257 | Stronger evidence for inventory, inbound, outbound, putaway, stock count, reporting, replenishment-task reads and packing-session reads |
| Workflow/inventory integrity | #204 concurrency, #208 partial-release preservation, #207 quantity boundaries | Useful focused tests; still needs combined integration on one current base |
| ZAP baseline | #235 latest disposable run #8 succeeded; artifact retained | Perimeter-only passive scan |
| Authenticated ZAP | #245 latest authenticated run #26 succeeded; artifact retained | 3 protected read routes; 0 Medium/High; limited scope |
| Authenticated performance | #256 k6 run #7 succeeded; artifact retained | Read-only baseline only, not business-workflow throughput |
| Final regression | Current main CI #952 failed; current fixes are on PR branches | NOT READY until current base is green after integration |

## 2. Current CI blocker and root cause

Main Backend CI run **#952** on SHA `81dd5f914544a71f0fc7ced3ed820f885d680db1` failed:

- 347 tests run; 0 assertion failures; 1 error; 2 skipped.
- Error: `WmsActiveBearerTokenLifecycleMySqlHttpTest` — MySQL Testcontainers startup failure.
- Root cause: the fixture uses a MySQL username longer than the server's 32-character limit.

The shorter fixture username `wms_active_token_http_test` is included in #234, #237, and #245. The latest #234 head **5dd3d01** has Backend CI #991 in progress and CodeQL #302 succeeded. Earlier attempts failed from a transient Maven distribution download and stale test signatures/imports; those issues were corrected on the latest head. Do not treat the fix as integrated until the CI run finishes and main is rerun.

PR #256 latest Backend CI #967 and CodeQL #278 succeeded on head **ef8d6c0**; k6 run #8 also succeeded. Its earlier Backend CI #962 failed on the shared fixture, which is fixed in the latest head.

## 3. Credential-version revocation gap found and fixed on PR #234 branch

Audit found that password changes invalidated existing access tokens but a previously issued tenant-selection token had no credential-version claim. Because tenant selection can mint a new access token, a stale selection token could otherwise refresh access during its short validity window after a password change.

The current PR #234 head **860bad2** now:
- includes the credential-version claim in tenant-selection tokens;
- validates the `tenant_selection` purpose and requires a valid version claim at the controller boundary;
- compares the selection token's version with the current membership-resolved user version before minting an access token;
- adds controller unit coverage and a MySQL integration regression proving an old selection token cannot mint a new access token after password change.

Backend CI and CodeQL are currently running on this new head. This fix is **not yet integrated**.

The unique HTTP runtime tests from #247 have also been ported into #234, and #253's null membership-resolution test was already ported there. #253 is closed; #247 is pending closure after #234's latest CI.

## 4. CI scope optimization defect found and fixed on PR #237 branch

The docs-only scope check was comparing the event's stale `base.sha` to `github.sha` (the synthetic PR merge SHA). When main advanced after the PR event, the diff included unrelated base-branch code changes, causing a documentation-only PR (#175) to run the full Maven regression.

The fix on PR #237 branch commit **42233c8** now fetches the current base ref and compares the PR head against the current merge-base. If fetching or merge-base fails, it safely falls back to full backend validation. The documentation `git diff --check` uses the same computed range.

A local Git simulation verified:
- stale-base documentation PR → documentation-only scope;
- stale-base backend PR → backend scope;
- shell syntax is valid.

CI run #966 completed successfully on the workflow-change head. The fix is CI-verified.

## 5. DAST baseline — latest actual artifact

PR #235 latest run **#8** succeeded (run ID `44941989564`) and retained artifact `wms-zap-baseline` (ID `14721579195`).

The report contains one Informational alert, `Non-Storable Content`, with 3 instances. No Low/Medium/High finding was reported in that artifact. The scan uses a disposable Compose project and does not target a developer or production database.

This remains unauthenticated/perimeter evidence only.

## 6. Authenticated DAST — latest actual artifact

PR #245 authenticated ZAP run **#26** succeeded (run ID `45040771306`) and retained artifact `wms-zap-authenticated` (ID `14761094821`).

Report review:
- 0 Medium and 0 High alerts.
- 2 Informational alert types: 30 instances of the scanner's 404 response-code informational alert, and 3 `Non-Storable Content` instances.
- Protected routes represented in the report: tenant profile, warehouse list, and warehouse detail.
- Authenticated pre-scan route contract and teardown steps succeeded.

The scan is deliberately limited to 3 protected read routes. It does **not** cover all WMS controllers, write operations, or business workflow attack paths.

The original CSRF findings were confirmed in PR #237 CodeQL SARIF (2 findings at `WmsSecurityConfiguration.java`). PR #249 CodeQL SARIF has 0 results after scoping the CSRF exemption to stateless bearer API routes. PR #245 includes that scoped fix plus the CORP header remediation.

## 7. Dependency scan disposition

Earlier PR #237 strict OSV scan succeeded on head **7a41eb3**. The scan was blocking (`fail-on-vuln: true`) and its log explicitly filtered two accepted Spring advisories:

- `CVE-2026-47890`
- `CVE-2026-47884`

The exceptions are based on source/config reachability review for the current REST-only runtime; they are **accepted as conditionally unreachable, not fixed vulnerabilities**. The SARIF output was empty only after those suppressions.

A new commit on #237 adds `effectiveUntil = 2026-12-31` to both exceptions. Current #237 Backend CI #966 and CodeQL #277 both succeeded on head **42233c8**. The exceptions remain a residual risk and must be reassessed before expiry or if view/SSE features are introduced.

## 8. Authenticated k6 read-only baseline — actual results

PR #256 run **#7** succeeded (run ID `45042767798`) with artifact `wms-k6-read-only-baseline` (ID `14762029263`).

Run profile and results:
- 5 virtual users, 20 seconds, 150 iterations.
- 450 HTTP requests across tenant profile, warehouse list, and warehouse detail.
- 150/150 checks passed for each endpoint; 450 checks passed; 0 failed HTTP requests.
- Average response duration: **9.20 ms**; p95: **17.98 ms**; maximum: **38.20 ms**.
- Observed request rate: **14.57 requests/second** on the GitHub Actions runner.

This is a disposable, authenticated, read-only baseline. It is **not** representative of receiving, putaway, picking, packing, inventory posting, replenishment, transfer, stock count, or shipment release throughput, and it is not a production-capacity claim.

## 9. Tenant/workflow coverage map

### MySQL/Testcontainers integration evidence
- #179 inventory posting cross-tenant and foreign-resource references, with balance/ledger/audit state assertions.
- #182 inbound receiving cross-tenant resource references.
- #183 outbound cross-tenant resource references.
- #197 putaway read isolation.
- #198 stock count/count-line isolation.
- #205 operational reporting tenant isolation.
- #257 replenishment-task detail tenant isolation and packing-session detail tenant isolation, both with MySQL A/B state assertions.

### Supplementary service/mock-JDBC coverage
- #176 warehouse/location boundary.
- #200 transfer.
- #201 picking/packing.
- #202 replenishment.
- #203 adjustment/shipping.
- #201 picking/packing (the new #257 MySQL integration adds packing-session detail coverage, but not the picking-task read path).
- These are useful focused checks, but they are not equivalent to real MySQL tenant A/B integration tests.

### Security lifecycle and HTTP boundary evidence
- #243 membership revocation and foreign tenant-switch denial in MySQL.
- #219 tenant/membership suspension denial.
- #244 HTTP tenant-context spoofing boundary.
- #248 role-administration permission matrix.
- #247 malformed/foreign JWT claim HTTP boundary.
- #230 exact public-route/method boundary, malformed JSON, and unsupported content type.
- #217 issuer/expiry, #216 error leakage, #212 unchanged-password rejection, #211 expired reset token, #209 CORS boundaries, #208 partial release, #204 concurrent outbound release, #207 quantity precision.

## 10. Remaining material gaps

1. Authenticated DAST covers only 3 read routes; WMS write endpoints and workflow state transitions are not scanned.
2. k6 covers read-only endpoints only. A representative authenticated business workflow benchmark remains unimplemented.
3. MySQL tenant A/B coverage is stronger after #257, but still uneven. Picking-task detail, transfers, adjustment/shipping, and some warehouse/location mutations rely on focused mocks or HTTP boundary tests rather than end-to-end MySQL ownership/unchanged-state assertions.
4. The new security/dependency changes have not been integrated together on one branch and rerun against current main.
5. CI scope fix, dependency exception expiry, credential-version invalidation, and current main regression must all complete on their latest SHAs.
6. Obsolete audit/spec/matrix PRs #174, #177, #178, #210, and #213 have been closed as superseded. #175 now holds refreshed closure evidence; #214 remains closed.

## 11. Dependency-safe merge order (no merge performed)

1. #245 — scoped CSRF/CORP + authenticated DAST evidence (CI and DAST currently green).
2. #237 — Spring dependency remediation, strict OSV, expiring advisory exceptions, and CI scope correction (latest CI pending).
3. #234 — credential-version invalidation and shortened Testcontainers username (latest CI pending).
4. #247 HTTP JWT runtime tests are consolidated into #234; integrate #248 role matrix, then #243/#244/#219 tenant/security lifecycle tests.
5. Integrate #230 route/method/content-type HTTP tests.
6. Integrate #235 baseline scan automation if retained.
7. Integrate #256 authenticated read-only k6 baseline (k6 workflow green; backend CI blocked by shared fixture on main).
8. Integrate tenant/workflow test PRs #179, #182, #183, #197, #198, #205, #257 and supplementary #176/#200–#203/#204/#207–#208. Revalidate each on the latest base.
9. Run full CI, CodeQL, strict OSV, DAST and relevant k6 workloads on the combined integration commit.

## 12. Closure decision

Stage 16 is **NOT READY** until:
- current main CI is green after the shared Testcontainers fixture fix;
- latest #234/#237 heads finish CI and CodeQL;
- CSRF findings are cleared on the integrated commit;
- all dependency exceptions have explicit expiry and review;
- tenant/workflow integration evidence is revalidated on the combined branch;
- authenticated business-workflow performance evidence is added (read-only k6 is not enough);
- PR inventory and merge order are refreshed;
- final full regression is green on the integrated commit.

No PR is merged by this audit. Main remains untouched.
