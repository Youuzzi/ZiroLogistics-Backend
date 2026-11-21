# Stage 16 Wave A — Full Security QA Execution Matrix

**Status:** Execution plan / evidence map; not a closure declaration  
**Scope:** A1 Authentication Abuse & Token Security; A2 Identity, Membership & Tenant Lifecycle; A3 Authorization & Permission Revocation; A4 HTTP Runtime Security; A5 Automated Security Scanning  
**Baseline:** main at 4141e74bf95ac575d83e5f151806d01f24fc47a5

## Execution model

Run independent test batches concurrently where practical. Do not wait for one CI run to finish before starting unrelated QA. Avoid duplicating existing evidence; extend tests where existing coverage is weak. PRs remain unmerged pending the user's explicit integration approval. Only CI evidence from actual workflow runs is marked verified.

Classify failures as production defect, test defect, fixture/setup defect, infrastructure/dependency failure, or scanner false-positive/accepted risk. Do not weaken assertions merely to make CI green.

## A1 — Authentication Abuse & Token Security

| Scenario | Evidence target | Status |
|---|---|---|
| Missing/malformed Bearer header | Protected HTTP route rejects safely | Existing evidence to audit |
| JWT signature invalid / decoder rejection | HTTP 401, no protected controller execution | Existing evidence to audit / gap candidate |
| Expired JWT | Runtime decoder rejects expired token | Gap candidate |
| Wrong issuer/audience | Runtime decoder rejects token | Gap candidate |
| Missing/invalid subject or membership claim | Authentication converter rejects | Existing unit evidence; HTTP evidence to audit |
| Wrong-purpose token | Tenant-selection / invitation / password action token cannot be used as access token | Gap candidate |
| Replayed/consumed one-time token | Exactly one consumption winner; later use rejected | Existing storage evidence to audit |
| Password reset expiry / token transaction boundary | Expired token rejected and persistence unchanged | PR #211 pending CI confirmation |
| Password change to same credential | Rejected before encoding/write | PR #212 pending CI confirmation |
| Credential change invalidation/revocation | Existing access behavior after password change is explicitly tested | Gap candidate |

## A2 — Identity, Membership & Tenant Lifecycle

| Scenario | Evidence target | Status |
|---|---|---|
| Inactive/revoked membership with existing token | Subsequent protected request denied | Existing converter tests; runtime gap candidate |
| Tenant suspended with active token | Protected tenant operation denied | Gap candidate |
| Tenant closed with active token | Protected tenant operation denied | Gap candidate |
| Tenant switch with foreign membership ID | Identity/tenant mismatch denied | Existing converter evidence; runtime audit |
| Manipulated tenant context/header/body | Server derives tenant from authenticated context and rejects mismatch | Gap candidate |
| Membership removed during request | Request cannot continue with stale authorization | Existing permission/revocation evidence to audit |

## A3 — Authorization & Permission Revocation

| Scenario | Evidence target | Status |
|---|---|---|
| No permission for protected operation | Server rejects, no side effect | Existing role/permission PRs; coverage audit |
| Vertical role escalation | Lower role cannot invoke admin operation directly | Existing PR #181 and #195; audit scope |
| Permission revocation while request is in flight | Operation follows documented synchronization boundary | Existing tests/PR #185; audit |
| Role demotion with old token | Current database authorization does not preserve stale privileges | Gap candidate |
| Read/create/update/delete/approve distinctions | Each operation enforces its specific permission | Gap candidate; route matrix needed |
| Anonymous access to protected endpoints | HTTP boundary denies | Existing PRs #180–#194; audit completeness |

## A4 — HTTP Runtime Security

| Scenario | Evidence target | Status |
|---|---|---|
| Exact public vs protected routes and methods | Public allowlist is narrow; all other paths/methods protected | Existing HTTP security tests; gap candidate |
| Authentication filter/decoder failure | Safe 401 response, no internal details | Gap candidate |
| Authorization denial | Stable 403/404 response, no sensitive resource existence leak | Existing API error contract; audit |
| Unexpected exception | Generic response; no SQL, stack trace, secrets, or class names | Existing API error contract; audit |
| Malformed JSON/content type | Safe client error; no server-side side effects | Existing API contract; expand runtime as needed |
| CORS allowlist/preflight | Exact configured origins, deny-by-default | PR #209; CI confirmation |
| Security headers | Runtime response headers verified | Gap candidate |
| Unsupported HTTP method | Rejected with safe response; no action side effects | Gap candidate |

## A5 — Automated Security Scanning

| Scan | Required evidence | Status |
|---|---|---|
| CodeQL | Workflow completed; alerts triaged | Existing foundation; current results to audit |
| OSV | Scan completed; dependency findings triaged by reachability/severity | Existing foundation; current results to audit |
| ZAP unauthenticated baseline | Workflow run and report reviewed | Baseline scope only; not authenticated DAST |
| Authenticated DAST | Disposable tenant/user/token fixture, safe target, route coverage, report artifact | Unverified / planned |
| API privilege-boundary automation | Actor-resource-action matrix, negative tests against protected API | Gap candidate |
| Finding triage | Each finding classified as confirmed defect, false positive, accepted risk, or remediation | Required |

## Batch execution and final gate

1. Audit existing tests and active PRs before each new batch to avoid duplication.
2. Create isolated QA branches and PRs with focused changes.
3. Run CI; independently continue other batches while CI is active.
4. Investigate every failure and preserve test intent.
5. Collect final evidence after batch runs finish.
6. Perform gap/duplication/quality audit against A1–A5.
7. Fix valid gaps and defects, add regression coverage, and retest.
8. Run full regression on the agreed integration result and review scanner findings.
9. Close Wave A only after all required gates have evidence and residual risks are explicit.

**Wave A remains OPEN.** This matrix is a planning/evidence aid; a row becomes verified only after the corresponding actual test/scan result is reviewed. The presence of test code or a green build alone does not prove full coverage.
