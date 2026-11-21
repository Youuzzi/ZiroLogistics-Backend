# Stage 16 — QA Evidence and Integration Matrix

**Status:** Working audit; Stage 16 remains OPEN.  
**Audit baseline:** `main` at `6f34e215c871c994a6ef2034cdd9b0cf824afe0c` (2026-11-21 audit).  
**Scope:** 64 open PRs observed in the repository. PR checks are evidence for their exact head commits only; they are not evidence that changes are integrated into `main`.

## Evidence rules

- A green PR CI proves that the tested branch head passed its configured workflow; it does not prove integration with other open PRs or the current `main`.
- A test file or specification is not proof of runtime execution. Distinguish source audit, test implementation, workflow execution, and reviewed scan findings.
- Do not merge by PR number or green badge alone. Resolve overlapping files, behavioral contracts, dependency order, and current-main compatibility first.
- Keep `main` unchanged during this audit. Integrate only after the complete map and dependency review are accepted.
- Do not add more tests merely to increase the count. Add or change coverage only where an evidenced P0/P1 defect or an integration contradiction requires it.

## Current repository and CI snapshot

- Open PR count: **64**.
- Main SHA at audit: `6f34e215c871c994a6ef2034cdd9b0cf824afe0c`; no PR in this audit has been merged.
- The ten PR branches affected by the Testcontainers fixture defect (#230, #234, #235, #236, #237, #238, #243, #244, #247, #248) were updated with the complete corrected lifecycle test fixture. Their new Backend CI and CodeQL runs completed successfully; #235's WMS DAST Baseline also completed successfully.
- #245 Authenticated WMS API DAST passed on its current recorded head. #249 Backend CI and CodeQL passed.
- PR heads #173–#253 reviewed in this matrix have successful configured build/test, CodeQL, and OSV checks where those checks are configured. PR #18 is an older documentation PR with Build and test passing. #253 was subsequently reconciled to combine strict claim-type validation with a null-membership guard; **its new head `a021e97d1ff41c9bf4fac1722f7514773d3e12c4` has a fresh CI run in progress**, so do not treat that new head as green yet.
- The k6 workflow in #173 is manual-dispatch only and has not been executed. Its document explicitly labels performance results PROPOSED / NOT EXECUTED.
- Successful static scans do not imply that findings have been triaged or that authenticated performance/DAST gates are complete.

## Wave A — Security evidence map

### A1 — Authentication abuse and token security

| PR | Scope | Evidence type / review note |
|---|---|---|
| #234 | Invalidate access tokens after credential changes | Production security behavior + tests; review token version invalidation against password/credential mutation flows |
| #238 | Malformed JWT claims at HTTP boundary | HTTP runtime test; tests safe 401 and prevents protected controller invocation |
| #247 | Malformed/foreign JWT claims at protected HTTP runtime | HTTP runtime + real converter/service boundary; overlaps claim validation changes with #238/#252 |
| #252 | Reject malformed JWT claim types before membership lookup | Converter production fix + unit test; non-string `mid` must be rejected without service lookup |
| #253 | Reject unresolved membership during JWT conversion | Converter null-result guard + unit test; reconciled to strict claim-type semantics; fresh CI pending after latest update |
| #217 | JWT issuer and expiry validators | Decoder/configuration tests; verify actual configured issuer, audience, expiry, and clock-skew contract |
| #211 | Expired password-reset token rejected transactionally | MySQL integration test; verify token remains unconsumed and password unchanged |
| #212 | Reject password change when new password equals current password | Service test; verify rejection occurs before transaction/write |
| #184 | Invalid bearer token at HTTP boundary | HTTP security test; overlaps general protected-route denial evidence |
| #185 | Permission revocation reflected on next token conversion | Converter/service test; cross-cutting with A3 |
| #251 | Testcontainers MySQL username length | Test infrastructure fix, not a security behavior test; shared fixture prerequisite for several PR branches |

**A1 integration notes:** #238, #247, and #252 carry the same strict JWT claim-type converter change. #253 originally had a conflicting expectation that numeric `mid=42` would be converted to string and looked up. That behavior contradicted #252. The #253 branch has now been reconciled: strict string-only claim validation is retained, the null access guard is added, a direct null-result unit test is added, and its duplicate `WmsJwtClaimsHttpSecurityTest` file is removed. Preferred order: #251 fixture, #252 strict claim contract, #253 null-result guard, then #238/#247 HTTP evidence. Re-check each resulting PR diff after earlier merges.

### A2 — Identity, membership, tenant lifecycle and isolation

| PR | Scope | Evidence type / review note |
|---|---|---|
| #243 | MySQL membership revocation and foreign tenant switch denial | MySQL integration; existing membership state and tenant switching |
| #244 | Tenant context cannot be spoofed by HTTP inputs | Controller HTTP test; overlaps #228/#236 |
| #236 | Reject tenant context overrides from HTTP input | Controller/service boundary test; overlaps #228/#244 |
| #228 | Reject tenant ID spoofing through headers, query and body | Controller HTTP test; same tenant-selector family as #236/#244 |
| #219 | Tenant and membership suspension deny access | Repository/service integration evidence for active token lifecycle |
| #218 | Membership rejection at protected HTTP runtime | HTTP runtime boundary |
| #206 | Foreign warehouse public-ID read boundary | Application/service test |
| #205 | Operational reporting tenant isolation in MySQL | MySQL integration test; reporting query tenant scoping |
| #203 | Adjustment and shipping cross-tenant isolation | Application/service boundary test |
| #202 | Replenishment cross-tenant task isolation | Application/service boundary test |
| #201 | Picking and packing cross-tenant resource boundaries | Application/service boundary test |
| #200 | Transfer cross-tenant resource isolation | Application/service boundary test |
| #198 | Stock-count cross-tenant read isolation | MySQL integration test |
| #197 | Putaway cross-tenant read isolation | MySQL integration test |
| #196 | Transfer tenant and membership authorization context | Service authorization test; overlaps A3 |
| #183 | Outbound foreign-tenant resource references | MySQL integration test |
| #182 | Inbound receiving cross-tenant negative tests | MySQL integration test |
| #179 | Inventory posting cross-tenant negative test | MySQL integration test |
| #176 | Warehouse tenant boundary checks | Service test |
| #180 | API authentication and tenant response boundaries | HTTP tests; cross-cutting with A4 |
| #174 | Tenant-isolation test gap audit | Source/test inventory audit only; not runtime proof |
| #177 | Inventory tenant-boundary source predicate audit | Source-code audit only; not runtime proof |
| #178 | Inventory cross-tenant negative-test specification | Specification only; not executed evidence |

**A2 integration notes:** #228/#236/#244 overlap on tenant selectors. Compare their exact assertions and covered input vectors before deciding whether all are independently valuable. #174/#177 are audits and #178 is a specification; do not count them as runtime tests.

### A3 — Authorization and permission revocation

| PR | Scope | Evidence type / review note |
|---|---|---|
| #248 | Dedicated role-administration permission matrix | Service permission matrix test |
| #181 | Prevent role-permission privilege escalation | Service authorization test |
| #185 | Permission revocation on next token conversion | Converter/service test; overlaps A1 |
| #196 | Transfer tenant and membership authorization context | Transfer authorization test; overlaps A2 |
| #199 | Tenant-scoped reporting authorization and queries | Reporting authorization/query test |
| #195 | Role-management HTTP authentication boundaries | HTTP authentication test; cross-cutting with A4 |
| #190 | Operational reporting HTTP auth boundary | HTTP authentication test; cross-cutting with A4 |

### A4 — HTTP runtime security boundaries

| PR | Scope | Evidence type / review note |
|---|---|---|
| #230 | Exact public authentication routes and method boundaries | Security-filter-chain allowlist/runtime test |
| #249 | Scope CSRF exemptions to stateless bearer API routes | Production security configuration + HTTP tests |
| #220 | Protected HTTP response security headers | HTTP runtime test |
| #216 | HTTP error information-leak assertions | API error contract test |
| #209 | CORS origin normalization and deny-by-default | CORS configuration/integration test |
| #184 | Invalid bearer-token HTTP boundary | HTTP runtime test; cross-cutting with A1 |
| #180 | API auth and tenant response boundaries | HTTP tests; cross-cutting with A2 |
| #191 | Inventory HTTP auth boundaries | HTTP test |
| #190 | Reporting HTTP auth boundary | HTTP test |
| #189 | Replenishment HTTP auth boundaries | HTTP test |
| #188 | Adjustment and shipping HTTP auth boundaries | HTTP test |
| #187 | Picking and putaway HTTP auth boundaries | HTTP test |
| #186 | Stock-count and packing HTTP auth boundaries | HTTP test |
| #194 | Transfer HTTP auth boundaries | HTTP test |
| #193 | Inbound and outbound HTTP auth boundaries | HTTP test |
| #192 | Invitation issuance and existing-account auth boundaries | HTTP test |
| #195 | Role-management HTTP auth boundaries | HTTP test; cross-cutting with A3 |
| #218 | Membership rejection at protected HTTP runtime | HTTP runtime test; cross-cutting with A2 |
| #238 | Malformed JWT claims at HTTP boundary | HTTP runtime test; cross-cutting with A1 |
| #247 | Malformed/foreign claims at protected HTTP runtime | HTTP runtime test; cross-cutting with A1 |
| #244 | Tenant-context spoofing through HTTP inputs | HTTP test; cross-cutting with A2 |

### A5 — Automated security scanning and finding triage

| PR | Scope | Evidence type / review note |
|---|---|---|
| #237 | Maven dependency remediation | Dependency remediation + OSV workflow evidence; review resolved dependency tree and scanner findings |
| #235 | Disposable WMS ZAP baseline | Unauthenticated baseline scan; successful run does not prove authenticated coverage |
| #245 | Authenticated ZAP API scan | Authenticated DAST workflow and OpenAPI route surface; review artifacts/findings and scan limitations |
| #249 | Archive CodeQL SARIF | Scanner artifact retention; does not itself triage alerts |

## Other Stage 16 workflow, data-integrity and performance evidence

| PR | Scope | Evidence type / review note |
|---|---|---|
| #207 | Inventory quantity decimal boundaries | Boundary test for DECIMAL precision, overflow, zero/negative, rounding and normalization |
| #208 | Partial outbound release rejection preserves state | Workflow-state and side-effect preservation test |
| #204 | Concurrent outbound shipment release transition | MySQL concurrency/transition test |
| #173 | k6 perimeter smoke plan and workflow | **Not executed**; manual workflow and Tier A denial-path smoke only. Not an authenticated business API benchmark |
| #205 | Reporting tenant isolation in MySQL | Also relevant to A2/A3 |
| #198–#203 | Cross-tenant workflow/reporting scenarios | Relevant to A2 and business-workflow integrity |

## Documentation and audit PRs

| PR | Artifact | Evidence status |
|---|---|---|
| #210 | Parallel QA wave evidence matrix | Working coordination document; not closure evidence |
| #213 | Remaining QA coverage gap register | Working gap register; its baseline SHA must be refreshed before treating it as current |
| #214 | This Wave A execution matrix | Working audit; not closure evidence |
| #175 | Stage 16.8 closure evidence gates | Gate definition only; Stage 16 remains open |
| #174 | Tenant isolation test gap audit | Source/test audit only |
| #177 | Inventory tenant boundary source audit | Source audit only |
| #178 | Inventory cross-tenant negative-test specification | Specification only |
| #18 | Authentication and session design | Older architecture/design document; review for staleness before considering integration into current documentation |

## Integration risks and proposed order

1. **Shared Testcontainers fixture:** #251 corrects the MySQL username limit. Several later branches carry the same fixture change. Prefer integrating #251 before dependent branches, then confirm duplicate fixture hunks become no-ops rather than conflicts.
2. **JWT converter overlap:** #252 introduces strict claim-type checks; #253 now includes those checks plus a null-result guard. #238/#247 also carry the same strict-claim converter hunk. Integrate #252 before #253, then inspect #238/#247 for test-only residual changes.
3. **Duplicate HTTP test path:** #247 and #253 originally added different versions of `WmsJwtClaimsHttpSecurityTest`. The duplicate file was removed from the latest #253 head; verify that remains true after integration.
4. **Tenant-selector overlap:** #228/#236/#244 cover similar header/query/body spoofing vectors. Compare exact test methods and controller routes; retain complementary cases, avoid redundant classes.
5. **Security scan distinction:** #235 is unauthenticated ZAP baseline; #245 is authenticated DAST. Both have different purposes and neither substitutes for manual finding triage.
6. **Performance gap:** #173 currently defines a low-load perimeter/denial-path smoke only. It does not prove authenticated endpoint throughput, business workflow performance, or production capacity.
7. **Documentation baselines:** #213 and earlier audit documents refer to older main SHA `4141e74bf95ac575d83e5f151806d01f24fc47a5`; refresh baselines and status after integration.

No merge order is authorized by this document. Before merging, fetch latest main and PR heads, re-run compare/conflict checks, and merge in small dependency-safe batches. Run full Backend CI after each batch and on the final integrated main SHA.

## Stage 16 closure gates

Stage 16 remains **OPEN / NOT READY** until all of the following have reviewed evidence:

- [ ] A1–A4 security behavior is mapped to actual tests and runtime outcomes, with overlaps reconciled.
- [ ] A5 CodeQL/OSV/ZAP findings are reviewed and classified, not merely marked green.
- [ ] Tenant isolation and workflow integrity tests are integrated and pass together on the combined codebase.
- [ ] Authenticated DAST artifacts and findings are reviewed.
- [ ] Authenticated performance evidence is run and reviewed, or the performance scope is explicitly deferred with a documented limitation.
- [ ] All integration conflicts and duplicate test classes are resolved.
- [ ] Full regression passes on the final integrated `main` commit.
- [ ] Final audit report records verified scope, residual risks, and a defensible READY / NOT READY decision.
