# Stage 16.8 — QA Closure Evidence Gates

**Status:** Prepared as a closure gate; Stage 16 remains OPEN until evidence is collected and reviewed.
**Scope:** QA/security/performance work already added to the repository, not a declaration that the system is production-ready.

## Evidence that must exist before closure

| Area | Evidence expected | Current state (at preparation) | Closure condition |
|---|---|---|---|
| Risk-to-test traceability (WP16.1) | Merged matrix and tests mapped to risk | PR #165 merged | Re-check main and link merged artifact |
| Coverage baseline (WP16.2) | JaCoCo report and recorded baseline | PR #166 merged; baseline only | Preserve baseline; document targeted deltas, avoid arbitrary coverage-only changes |
| Dependency scanning (WP16.3) | OSV report with finding disposition | PR #168 merged; scanner reports findings | Every critical/high finding is triaged with owner, remediation or documented exception; do not equate scanner workflow success with zero findings |
| Static analysis (WP16.4) | CodeQL workflow result and alert review | PR #169 merged; CI success reported | Confirm latest run and review alert state; report findings accurately |
| Security verification (WP16.5) | Controller/authorization test results and gaps | PR #170 merged; PR #171 controller-route review merged | Close remaining runtime-test gaps or document risk/limits; review evidence must identify actual tested routes |
| DAST (WP16.6) | ZAP artifact and triage | PR #172 merged; workflow is manual; scan not yet executed | Run against disposable environment, retain artifact, review findings and record false positives/remediation |
| Performance (WP16.7) | k6 summary artifact and interpretation | PR #173 draft; low-load perimeter smoke only; benchmark not yet executed | Mark PR ready/merge after review, run workflow, retain report; state endpoint, load, limitations, and observed results |
| Tenant isolation follow-up | Cross-tenant boundary gap audit and runtime test follow-up | PR #174 open; source audit, not runtime proof | Merge audit after CI; prioritize actual Tenant A/B tests for resource ID read/write and no-change-on-denial evidence |

## ZAP run record (fill only from an actual workflow run)

- Run URL / run ID:
- Target disposable environment:
- Scan date/time (UTC):
- Artifact name and link:
- Alerts by risk:
- Findings reviewed / false positives:
- Remediation PRs or accepted exceptions:
- Reviewer:

## k6 run record (fill only from an actual workflow run)

- Run URL / run ID:
- Environment:
- Script revision:
- Scenario and endpoint:
- VUs / duration:
- Request count:
- Failure rate:
- Latency (p50 / p90 / p95 / p99, if produced):
- Threshold result:
- Artifact:
- Interpretation and limitations:

## Tenant-isolation follow-up record

- Tenant A and Tenant B fixture details:
- Cross-tenant resource identifiers exercised:
- HTTP routes covered:
- Expected denial response:
- Before/after persistence assertion:
- CI run and result:

## QA closure decision

Do not close Stage 16 solely because all CI workflows are green. Green CI indicates the configured checks completed successfully; it does not mean manual DAST/performance workflows ran, scan findings are absent, every controller is covered, or the tenant-isolation gap has been fully tested.

Closure decision: **NOT READY — evidence pending** until the records above are populated and reviewed. This status must be updated only after the evidence is available.
