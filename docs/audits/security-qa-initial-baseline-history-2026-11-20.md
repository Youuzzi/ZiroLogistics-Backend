> **Historical baseline archive:** Preserved from the former `WMS_STAGE_16_QA_SECURITY_BASELINE.md` at baseline commit `a581d696e327ab5ba6cd70e8375caff9109b02fe`. Its inventory (147 production Java files, 73 test files), absent-tooling observations, and work-package plan describe the repository at that earlier checkpoint only. They are retained for provenance, not as current implementation or release instructions. Use [Current Security and QA Baseline](security-qa-baseline.md), [Security and QA Verification Status](security-qa-closure-criteria.md), and the [Backend Improvement Roadmap](../roadmap/backend-improvement-roadmap.md) for current state.

# Historical QA and Security Baseline — 2026-11-20

**Baseline commit:** `a581d696e327ab5ba6cd70e8375caff9109b02fe`
**Scope:** WMS backend quality assurance, security verification, dependency risk, and performance readiness.
**Status:** Baseline inventory completed; execution and measurement WPs remain open.

## 1. Audit rules

- Preserve the existing WMS regression suite; do not replace or weaken tests to make a tool pass.
- Add tests only for a traceable requirement, identified risk, observed defect, or uncovered boundary.
- Separate source inspection from runtime evidence. A source-level observation is not a passing test.
- Use disposable MySQL 8.4 Testcontainers or isolated Compose environments. Never use `zirologistics_db`, `zirowms_v1`, a developer-owned database, or production data for QA.
- Use synthetic accounts, tenant fixtures, secrets, and inventory data.
- Record exact commit, tool version, invocation, environment, result, and evidence location.
- Do not claim production readiness from a green CI pipeline alone.

Evidence labels:
- **VERIFIED:** reproducible runtime/tool/test evidence.
- **PARTIALLY VERIFIED:** some paths or controls are exercised, but scope is incomplete.
- **UNVERIFIED:** no sufficient evidence yet.
- **INFERRED:** source/configuration inspection only.
- **PROPOSED:** planned control or test not yet implemented.

## 2. Repository baseline inventory

The inventory below was obtained from the repository tree and build/workflow configuration at the baseline commit.

| Area | Observed baseline | Assessment |
|---|---|---|
| Production Java | 147 Java source files | WMS production source tree is consolidated under the WMS runtime. |
| Java tests | 73 `*Test.java` files | Broad test ownership exists across security, identity, migration, inventory, and workflows. This is a file count, not a test-method count or coverage metric. |
| QA workspace | No `qa/` directory before this baseline | Create a separate place for test plans, security evidence, performance scenarios, and release evidence. |
| Coverage | No JaCoCo plugin/configuration found in `pom.xml` or repository tree | Coverage percentage is currently **UNVERIFIED**. Establish a baseline before choosing thresholds. |
| Dependency scanning | No OWASP Dependency-Check configuration found | Dependency vulnerability assessment is not yet automated in repository CI. |
| Static security analysis | No CodeQL workflow/configuration found | Automated code security analysis is not yet configured. |
| Dynamic security testing | No ZAP configuration found | DAST has not been established. |
| Performance testing | No k6 scripts/configuration found | Performance baselines and capacity targets are not yet measured. |
| CI | GitHub Actions, Java 21, Maven Wrapper, Compose validation, full regression, packaged WMS entry-point check, Docker build | Existing CI is a useful build/regression gate; it is not a complete QA/security program. |

### Existing test file distribution

| Test area | Files |
|---|---:|
| Runtime boundary and tenant identity persistence | 2 |
| Authorization | 2 |
| Bootstrap | 1 |
| Identity | 14 |
| Inbound | 1 |
| Inventory | 4 |
| Migration | 13 |
| Outbound | 1 |
| Putaway | 1 |
| Replenishment | 2 |
| Security | 21 |
| Stock count | 1 |
| Tenancy | 5 |
| Transfer | 2 |
| Warehouse | 3 |
| **Total** | **73** |

The count does not prove that every requirement, branch, controller, tenant boundary, or failure mode is covered. A later WP must map tests to critical risks and identify evidence gaps.

## 3. Existing CI baseline

The current backend workflow performs, on Main pushes:
1. Java 21 setup.
2. Maven Wrapper execution.
3. WMS Compose configuration validation.
4. `./mvnw --batch-mode --no-transfer-progress -Pwms-runtime clean verify`.
5. Packaged JAR Start-Class verification against `WmsRuntimeApplication`.
6. WMS Docker image build.

Documentation-only pull requests run `git diff --check`; backend-relevant pull requests run the full regression path.

The post-structural-cleanup Main CI is recorded separately in the repository history. This baseline document does not infer a new test count from a CI run whose detailed Surefire summary has not been captured here.

## 4. Risk-led QA scope

Prioritize evidence for controls whose failure could cause tenant data exposure, unauthorized operations, or inventory corruption.

### P0 — release-blocking security and integrity risks

- Tenant isolation: direct cross-tenant public-ID substitution on reads, writes, list/search, and nested resources.
- Authentication: disabled/inactive users, revoked memberships, invalid/expired/wrong-issuer/wrong-audience JWTs, and fail-closed configuration.
- Authorization: persisted role/permission enforcement, privilege escalation, and revocation behavior.
- Inventory integrity: atomic ledger/balance posting, idempotency, concurrency, capacity constraints, and safe failure/rollback.
- Workflow boundaries: invalid state transitions, duplicate commands, cancellation/reversal semantics, and cross-tenant references.
- Secrets and configuration: no committed credentials/private keys, secure defaults, and no accidental public database exposure.
- Dependency vulnerabilities: direct and transitive runtime dependencies, severity, exploitability, and remediation status.

### P1 — operational reliability

- Input validation and error disclosure consistency.
- Login throttling and trusted-proxy/client-address handling.
- CORS allowlist and browser preflight behavior.
- Migration reproducibility on disposable MySQL 8.4.
- Container execution, health/readiness behavior, and configuration failure modes.
- Backup/restore and recovery evidence (to be completed with Stage 17 deployment work).

### P2 — performance and maintainability

- Representative read paths and bounded collection endpoints.
- Inventory posting and concurrent receipt/shipment workloads.
- Authentication/password hashing capacity and throttling behavior.
- Query plans and database contention under measured workloads.
- Coverage trend and maintainability signals.

## 5. Staged execution plan

| WP | Work | Exit evidence |
|---|---|---|
| 16.1 | Test inventory and risk-to-test traceability | Critical workflows, tenant/security boundaries, and known gaps mapped to existing tests; no blanket test expansion. |
| 16.2 | JaCoCo coverage instrumentation | Reproducible unit/integration coverage report; baseline recorded by package and critical module. No arbitrary threshold before baseline review. |
| 16.3 | Dependency vulnerability scanning | Reproducible direct/transitive dependency report, triage policy, documented suppressions with expiry/owner, and remediation plan for actionable findings. |
| 16.4 | CodeQL/static security analysis | Java security analysis in CI with findings triaged; false-positive handling documented. |
| 16.5 | Focused security verification | Executed negative tests for tenant isolation, authn/authz, input/error handling, throttling, CORS, and secrets/configuration. |
| 16.6 | ZAP DAST in isolation | Baseline/API scan against disposable WMS environment with synthetic data and test keys; authenticated scan only after safe test identity/bootstrap is provisioned. |
| 16.7 | k6 performance baseline | Workload model, test data, latency/throughput/error-rate measurements, and database/resource observations for agreed critical paths. |
| 16.8 | QA/security closure and release gate | Findings classified, critical issues closed or explicitly accepted, residual risks documented, and evidence indexed. |

Work is sequenced so that static/dependency analysis and test mapping can proceed before isolated DAST and performance environments are ready. Do not run ZAP or k6 against production or a developer-owned database.

## 6. Tooling policy

Prefer free/open-source tools and native GitHub Actions where practical:

- **JUnit 5 + Testcontainers:** existing automated regression foundation.
- **JaCoCo:** coverage measurement; do not optimize for a single aggregate percentage.
- **OWASP Dependency-Check:** dependency CVE inventory and triage.
- **CodeQL:** Java static analysis through GitHub Actions where repository plan/permissions support it.
- **OWASP ZAP:** dynamic scan only against an isolated disposable environment.
- **k6:** reproducible load scenarios and threshold reporting.
- **SonarQube Community Build:** optional, only if it provides actionable value beyond existing checks; do not add a self-hosted service without operational ownership.

Tool availability, licensing, GitHub plan limits, and action versions must be confirmed when each WP is implemented. Avoid adding overlapping scanners that create noise without an owner.

## 7. Initial decisions and constraints

1. Keep all 73 existing test files unless a later test audit proves a specific test obsolete or duplicated with no unique invariant.
2. Do not move Java tests into `qa/`; the directory is for plans and evidence only.
3. Do not set coverage percentage gates until the first JaCoCo baseline is measured and critical modules are reviewed.
4. Do not automatically fail builds for every CVE before triage rules, reachability, severity policy, and suppression governance are defined.
5. Do not add ZAP/k6 to every pull request. Use controlled scheduled/manual jobs and isolated environments first; promote stable, fast checks into CI later.
6. Keep the backend-only sequence. Frontend work remains gated until backend QA/security, deployment, and production readiness are complete.

## 8. Current conclusion

The repository has a substantial existing automated regression suite and a working build/package/container CI path. However, test-file counts do not establish test adequacy, and the absence of coverage/security/performance tooling means those areas need explicit evidence.

**Stage 16 is not complete.** The next action is WP16.1: map high-risk requirements and current tests, then implement measurement/scanning in small reviewable increments.
