# Stage 16.8 — QA Closure Evidence Gates

**Status: Stage 16 remains OPEN / NOT READY.** Updated 2026-11-22 from current PR heads, GitHub Actions runs, and retained artifacts. This is evidence tracking, not a production-readiness declaration.

## Current PR/CI snapshot

- #175 closure evidence: Backend CI #994 and CodeQL #305 succeeded.
- #237 dependency remediation/CI scope fix: Backend CI #966 and CodeQL #277 succeeded on head **42233c8**.
- #256 read-only k6: Backend CI #967, CodeQL #278, and k6 run #8 succeeded on head **ef8d6c0**.
- #234 credential invalidation: Backend CI #991 is in progress on head **5dd3d01**; CodeQL #302 succeeded. Older attempts failed from transient Maven distribution download and stale test signatures/imports, corrected on the latest head.
- #245 authenticated DAST: Backend CI #993 is in progress on head **65205ee**; CodeQL #304 succeeded; authenticated ZAP run #28 succeeded on the current head.
- #257 MySQL outbound/picking/packing/replenishment tenant isolation: Backend CI #998 and CodeQL #309 are in progress on head **b7d877e**. The earlier #990 compile failure from a missing exception import was corrected; a replenishment read-permission fixture mismatch was also corrected on this head.
- #202 mock replenishment tenant boundary: Backend CI #997 and CodeQL #308 are in progress on head **3b7ecf6**; the earlier failing permission fixture has been corrected. Its test is now redundant with the stronger #257 coverage and should be closed after #257 CI is green.
- #235 baseline ZAP: Backend CI #921, CodeQL #233, and baseline scan #8 succeeded.
- #230 public auth route/method boundary tests are green.
- #200 transfer and #203 adjustment/shipping tenant-boundary tests were reopened after audit; their Backend CI and CodeQL were green. They are mock-JDBC supplemental evidence, not MySQL integration.

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

PR #237 strict OSV scan succeeded on head **42233c8** (latest CI #966). The scan was blocking (`fail-on-vuln: true`) and its log explicitly filtered two accepted Spring advisories:

- `CVE-2026-47890`
- `CVE-2026-47884`

The exceptions are based on source/config reachability review for the current REST-only runtime; they are **accepted as conditionally unreachable, not fixed vulnerabilities**. The SARIF output was empty only after those suppressions.

Reachability review: the Maven tree resolves Spring Framework **6.2.19**, which is in the affected range for both advisories. I inspected all 21 WMS controller files; each is a `@RestController`, and none contains `SseEmitter`, `ServerSentEvent`, `text/event-stream`, `XsltView`, or `ModelAndView`. The WMS runtime/security configuration also has no view-controller/SSE references. The specific vendor prerequisites therefore were not found in this runtime. Spring's vendor advisory rates CVE-2026-47890 LOW and CVE-2026-47884 MEDIUM, while GitHub Advisory Database rates both CRITICAL; record the rating mismatch, do not treat the suppressions as remediation.

Official vendor details: [CVE-2026-47890](https://spring.io/security/cve-2026-47890/) requires SSE with view fragments; [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) requires XsltView and a catch-all view-rendering mapping. The vendor lists Spring Framework 6.2.20 as Enterprise Support only and 7.0.9 as OSS fixed. The 6.2.19 dependency remains affected at framework level; the temporary exception is a residual risk.

A new commit on #237 adds `effectiveUntil = 2026-12-31` to both exceptions. Current #237 Backend CI #966 and CodeQL #277 both succeeded on head **42233c8**. The exceptions remain a residual risk and must be reassessed before expiry or if view/SSE features are introduced.

## 8. Authenticated k6 read-only baseline — actual results

PR #256 run **#7** succeeded (run ID `45042767798`) with artifact `wms-k6-read-only-baseline` (ID `14762029263`).

Run profile and results:
- 5 virtual users, 20 seconds, 150 iterations.
- 450 HTTP requests across tenant profile, warehouse list, and warehouse detail.
- 150/150 checks passed for each endpoint; 450 checks passed; 0 failed HTTP requests.
- Average response duration: **5.95 ms**; p95: **10.99 ms**; maximum: **24.61 ms**.
- Observed request rate: **14.72 requests/second** on the GitHub Actions runner (latest k6 run #8).

This is a disposable, authenticated, read-only baseline. It is **not** representative of receiving, putaway, picking, packing, inventory posting, replenishment, transfer, stock count, or shipment release throughput, and it is not a production-capacity claim.

## 9. Tenant/workflow coverage map

### MySQL/Testcontainers integration evidence
- #179 inventory posting cross-tenant and foreign-resource references, with balance/ledger/audit state assertions.
- #182 inbound receiving cross-tenant resource references.
- #183 outbound cross-tenant resource references.
- #197 putaway read isolation.
- #198 stock count/count-line isolation.
- #205 operational reporting tenant isolation.
- #257 MySQL A/B detail-read isolation for outbound shipments, picking tasks, packing sessions, and manual replenishment tasks; ownership/persistence assertions remain under Tenant A.

### Supplementary service/mock-JDBC coverage
- #176 warehouse/location boundary.
- #200 transfer.
- #201 picking/packing.
- #202 replenishment (mock test; superseded by #257 MySQL coverage).
- #203 adjustment/shipping (reopened, mock-JDBC supplemental test).
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
3. MySQL tenant A/B coverage is stronger after #257, but still uneven. Transfer cross-tenant resources, adjustment/shipping write paths, and warehouse/location mutations rely on focused mocks or HTTP boundary tests rather than end-to-end MySQL ownership/unchanged-state assertions.
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
8. Integrate tenant/workflow MySQL PRs #179, #182, #183, #197, #198, #205, and #257; retain #176/#200/#201/#203 as supplemental mocks, and close #202 after #257 CI is green. Revalidate each on the latest base.
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


## 13. Gate 1 PR audit update — 2026-11-23

**Gate 1 remains OPEN. No PR has been merged.** This update supersedes the older head/status snapshot above where it conflicts.

### Latest verified CI heads

| PR | Head | Backend CI | CodeQL | Notes |
|---|---|---|---|---|
| #257 | 1b389449 | Success | Success | Consolidates MySQL tenant reads plus outbound foreign-reference, rejected partial-release, and concurrent-release tests |
| #244 | 9c661a7f | Success | Success | Tenant-selector spoofing plus profile response/internal-ID and authentication-boundary tests |
| #248 | 0039e09e | Success | Success | Role permission matrix plus protected inventory-posting delegation test |
| #200 | 21f0197f | Success | Success | Transfer cross-tenant MySQL boundary |
| #203 | 6dbee37c | Success | Success | Adjustment/shipping tenant-boundary supplemental tests |
| #245 | ccc94a4c | Success | Success | Scoped CSRF/CORP and authenticated DAST automation; header-runtime test is also present in #234 |
| #237 | 42233c83 | Success | Success | Dependency remediation, strict OSV, expiring exceptions, and CI scope correction |
| #256 | ef8d6c04 | Success | Success | Authenticated read-only k6 baseline |
| #259 | 6aeac271 | Success | Success | Concurrent one-time token consumption race across every token purpose |
| #234 | 37f8e3a4 | In progress | Success | Credential-version token invalidation plus consolidated JWT, warehouse, transfer, password-reset, and decoder-boundary tests |
| #205 | aca252ff | No run on this head yet | No run on this head yet | Reporting MySQL tenant isolation remains unique; previous Backend CI attempt was cancelled after the branch update |
| #176 | 7e9b66bc | No run on this head | No run on this head | Its warehouse tests are also included in #234; the earlier failing run was on the pre-fixture head |

The above conclusions are tied to the exact head SHAs shown. A green run on an older SHA is not treated as evidence for a newer head.

### Consolidated test coverage recovered during the audit

- #234 now carries the unique warehouse boundary tests from #176, permission-revocation conversion coverage from #185, transfer membership-context tests from #196, expired reset-token coverage from #211, and invalid-bearer decoder HTTP coverage from #184. Its credential-version implementation also covers the JWT claim-type/null-resolution fixes previously isolated in #247/#252/#253/#258.
- #257 now carries the unique outbound foreign warehouse/customer/item reference test from #183, rejected partial-release state-preservation test from #208, and concurrent release single-winner test from #204. The replenishment task tenant-read methods from #202 are already present there.
- #244 now carries #180's tenant-profile response redaction and unauthenticated mutation assertions, alongside the tenant-selector spoofing tests from #228/#236.
- #248 now carries #181's protected WMS_INVENTORY_POST delegation boundary test.
- #245 contains the runtime security-header assertion originally left out of closed PR #220; the same assertion is also present in #234.
- #205 contains the recovered reporting service tenant-boundary unit test from #199 and retains its separate MySQL tenant-isolation integration test.
- #259 restores the all-purpose concurrent token-consumption race test from closed PR #232.

### Closed-PR disposition

Closed does not mean irrelevant. The audit compared each candidate with main and the active PR heads:

- **Already present or superseded:** #233 request-parsing tests are present on main; #225 closed-tenant access-resolution test is present on main; #239 role-management assertions are already present on main/the active role test branch; #202 is covered by #257; #247/#252/#253 JWT boundaries are covered by #234/#258; #241/#250's unsupported-content-type work is covered by merged #240.
- **Recovered into active work:** #199/#206 into reporting/warehouse coverage; #181 into #248; #184/#185/#196/#211/#176 into #234; #183/#204/#208 into #257; #220 into #245/#234; #228/#236/#180 profile assertions into #244; #232 into #259.
- **Superseded implementation/automation:** #242's dependency and CI remediation is represented by the active #237 branch; #246 authenticated DAST is superseded by #245; #231 baseline DAST is superseded by #235.
- **Historical or stale evidence, not code to restore blindly:** #174/#177/#178/#210/#213/#214 are audit/spec artifacts whose snapshots need reconciliation before reuse. #173 is a 403-only unauthenticated perimeter smoke; it is not treated as a business-performance test and is not restored as a substitute for the authenticated read-only baseline in #256.

### Shared Testcontainers fixture root cause

The shared failure was MySQL error 1470: the original fixture username wms_active_token_lifecycle_http_test exceeded MySQL's 32-character account-name limit. The corrected fixture uses a short username and ephemeral RSA test keys, and mocks the email-outbox encryption dependency for this isolated HTTP lifecycle test. The corrected fixture has been propagated to the active unique QA branches. PR #176's old failure is therefore not attributed to its warehouse assertions.

### Remaining integration risks

1. #234 and #245 share identical WmsSecurityConfiguration and WmsHttpSecurityHeadersRuntimeTest; #234 has the broader HTTP route/malformed-request test set, while #245 contributes authenticated DAST automation and its OpenAPI artifacts. Keep their merge order coordinated.
2. #234 is still running Backend CI on its latest consolidated head. Do not declare credential-version invalidation complete until that exact run succeeds.
3. #205's reporting MySQL integration test has no completed Backend CI run on its latest head yet. It remains a Gate 1 blocker for reporting tenant-isolation evidence.
4. Several older open branches were updated with the corrected shared fixture but have not produced a new workflow run on the updated head. Treat those heads as unverified until their changes are validated in a consolidated current-base CI run or a fresh PR workflow run.
5. The two Spring Framework advisories in osv-scanner.toml remain accepted residual risk until 2026-12-31; they are not fixed vulnerabilities. Reassess before expiry and whenever SSE/view-rendering features are introduced.

### Gate 1 closure criteria

Gate 1 is not complete until every unique change is represented in an active PR, duplicates and stale artifacts are explicitly dispositioned, all retained changes have CI/CodeQL evidence on a current head, the reporting integration test is validated, and a final combined regression succeeds. No merge or PR closure is authorized by this audit.


## 14. Post-integration CI refresh — 2026-11-23

The latest exact-head checks have now completed for the main consolidated branches:

- #234 — head 37f8e3a4: Backend CI and CodeQL both SUCCESS.
- #259 — head 6aeac271: Backend CI and CodeQL both SUCCESS.
- #257 — head 1b389449: Backend CI and CodeQL both SUCCESS.
- #244 — head 9c661a7f: Backend CI and CodeQL both SUCCESS.
- #248 — head 0039e09e: Backend CI and CodeQL both SUCCESS.
- #200 — head 21f0197f: Backend CI and CodeQL both SUCCESS.
- #203 — head 6dbee37c: Backend CI and CodeQL both SUCCESS.
- #245 — head ccc94a4c: Backend CI and CodeQL both SUCCESS.
- #237 — head 42233c83: Backend CI and CodeQL both SUCCESS.
- #256 — head ef8d6c04: Backend CI and CodeQL both SUCCESS.
- #205 — head 6f6ea4f3: CodeQL SUCCESS; Backend CI IN PROGRESS. This head now includes current main as a merge parent and is mergeable.
- #217 — rebased onto current main and now mergeable; no completed CI run has yet been observed on its rebased head.
- #176 — still not mergeable as a standalone PR, but its unique warehouse tests are included in #234, whose current-head CI is green.

### Draft PRs still needing disposition

The following 21 PRs remain drafts and currently report a dirty/non-mergeable relationship with main: #219, #216, #212, #209, #207, #201, #198, #197, #195, #194, #193, #192, #191, #190, #189, #188, #187, #186, #182, #180, and #179.

They are not considered release-ready. Their unique tests must either be integrated into a green current-base PR or the draft branch must be rebased and validated before it is promoted. This is separate from the already-green consolidated PRs above. Do not close these drafts automatically; first compare their unique coverage against the consolidated branches.

#205's earlier cancelled run was on an older head and is not counted as current-head evidence. Its new Backend CI run must finish successfully before reporting tenant-isolation coverage can be marked validated.
