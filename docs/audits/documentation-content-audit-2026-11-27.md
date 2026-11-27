# Documentation Content Audit — 2026-11-27

**Target:** `docs/backend-handoff-and-documentation-refresh`  
**Code checkpoint:** `main` at `148177826c5eebd56b6134851e5d901809549448`  
**Scope of this pass:** repository-wide documentation tree/name/link scan; current-reference reconciliation against source, configuration, migrations, and tests for runtime/deployment, API compatibility, tenant identity/RBAC, tenant isolation, MFA, platform control plane, temporal behavior, security/QA, and the frontend handoff. Historical evidence is retained with its original baseline and explicit status rather than rewritten as current truth.  
**Status:** Current reference and handoff reconciliation completed. Historical findings have not all been independently re-run, and this is not an endpoint-by-endpoint API or production-security certification.

## Executive disposition

PR #367 is a documentation refresh and handoff, not a full documentation cleanup. The documentation tree still contains uppercase/underscore filenames, historical stage/work-package terms in content, and overlapping documents. Do not interpret the PR's earlier rename summary as a repository-wide naming standardization.

The current repository contains active references, dated audits, historical baselines, design proposals, plans, test inventories, and source schema artifacts. They must not all be rewritten as if they described the same current implementation.

## Repository-wide filename findings

The scan found **many non-kebab-case names still present**. Examples include:

- `docs/architecture/PLATFORM_ADMINISTRATION_AND_OPERATIONS.md`
- `docs/architecture/PLATFORM_AUTHORIZATION_CONTRACT.md`
- `docs/architecture/WMS_AUTHENTICATION_AND_TOKEN_ARCHITECTURE.md`
- `docs/architecture/WMS_INVENTORY_CORE_ARCHITECTURE.md`
- `docs/audits/BACKEND_ARCHITECTURE_AND_DOCUMENTATION_AUDIT.md`
- `docs/runtime/WMS_RUNTIME_BOUNDARY.md`
- `docs/deployment/WMS_LOCAL_DOCKER_DEPLOYMENT.md`

**Naming disposition:** Current active entry points should use descriptive lowercase kebab-case. Historical evidence can retain dates and original identifiers in its content, but filenames should describe subject and evidence type. The repository has not yet been fully renamed to this convention. Any rename must update every inbound Markdown link and index reference and be checked for broken links.

## Current checkpoint facts used by the handoff

- Java 21, Spring Boot 3.5.16, MySQL 8.4, Flyway 11.20.3.
- WMS runtime entry point: `WmsRuntimeApplication`; Maven profile `wms-runtime`; Spring profile `wms`.
- Dedicated runtime database: `zirologistics_wms`; migrations V1–V32. Applied migrations are immutable; future changes are additive from V33.
- API prefix: `/api/wms/v1`. Existing endpoint-specific response shapes and successful status conventions are compatibility constraints.
- Platform control-plane foundations now include V29 operator/role/permission/MFA persistence, V30 support-grant governance, platform JWT and permission services, role administration, tenant-lifecycle authorization, and support-grant administration. End-to-end platform MFA/login HTTP, initial Platform Administrator provisioning, platform controllers, and support-grant scope consumption remain incomplete.
- Backend work is paused temporarily for frontend foundation work. This is not security completion or production-readiness certification.

## Security and integration boundaries

- Tenant and platform JWT trust domains are separated; cross-signature rejection is tested with real RSA keys/decoders.
- Tenant credential-version enforcement and MFA persistence foundations are merged. Tenant MFA end-to-end enrollment, verification, step-up, recovery, and transactional one-time proof consumption are incomplete and MFA remains disabled by default.
- Platform identity/security primitives exist, but controlled initial Platform Administrator provisioning and a complete platform HTTP surface are not established.
- Support-grant governance exists; a runtime scoped-access consumer/enforcement path was not identified in the reviewed source slice.
- Selected tenant-isolation reviews and fixes cover only their reviewed paths. Complete route-to-query verification, nested/bulk foreign-ID negative tests, concurrency, rollback/audit, and worker/command tenant-context verification remain open.
- Frontend work may use mock repositories. Keep mock repositories and API adapters replaceable and do not imply incomplete security features are available.

## Documentation groups and required treatment

| Group | Required treatment |
|---|---|
| README and docs index | Current entry points, checkpoint, setup, and accurate status; links must resolve. |
| API reference/contracts | Compatibility-oriented; route inventory must be source-derived. Do not assert endpoint behavior not verified. Preserve payload/status differences. |
| Runtime/deployment/database | Keep runtime profile, database boundary, migration count, and environment variables aligned with source/config. Legacy databases are not migration targets. |
| Architecture/design | Clearly label implemented, partially implemented, proposed, and historical. Design is not implementation evidence. |
| Security audits | Preserve date, exact scope, checkpoint, evidence type, and limitations. Do not generalize partial reviews into repository-wide assurance. |
| QA/testing | Distinguish tests authored, locally executed, CI-passed, source-reviewed, and not verified. |
| Roadmap/handoff | Maintain these as the current backend pause/resume guides; avoid duplicating status in many documents. |
| Historical documents | Retain useful evidence. Add a historical/superseded banner and successor link when a stale document may be mistaken for current truth. |

## Confirmed cleanup gaps

1. Repository-wide filename convention is not complete; uppercase and underscore names remain.
2. Documentation overlaps substantially (architecture, audit, baseline, contract, implementation plan). Canonical ownership and duplication have not been resolved for every subject.
3. Stage/work-package identifiers remain in historical document content and in some filenames. This is acceptable only where clearly historical; active entry points should not depend on these identifiers.
4. The documentation index needs a link audit against the exact tree. No blanket assertion is made that every relative link is valid.
5. The WMS API v1 reference is explicitly compatibility-oriented and does not claim to be a complete endpoint/DTO catalogue. Full route-to-controller-to-DTO/status/pagination verification remains a backend follow-up tracked in the roadmap.
6. Historical audits retain their original source checkpoints; their findings are not silently promoted to current implementation claims.

## Frontend handoff guardrails

- Use synthetic/mock data behind repository/service interfaces, not directly embedded in screens.
- Keep API adapters separate so mock-to-real integration does not require screen rewrites.
- Do not assume tenant MFA, support access, Platform Administrator bootstrap, or production operations are complete.
- Tenant-local interpretation of scheduling fields remains a backend contract issue; do not invent timezone semantics in frontend.
- Preserve endpoint-specific v1 payload and success-code behavior once integration begins.

## Completion criteria for a truly exhaustive docs cleanup

- [ ] Rename all active docs to descriptive lowercase kebab-case; decide whether historical evidence filenames also need normalization.
- [ ] Update and verify all relative links after renames.
- [ ] Establish one canonical doc per subject; mark duplicates, superseded plans, and historical snapshots.
- [ ] Reconcile every active reference against source/config/tests and state the exact checkpoint.
- [ ] Verify API endpoints, DTOs, validation, response bodies, status codes, pagination, and temporal behavior from controllers and tests.
- [ ] Reconcile security claims with code, negative tests, CI evidence, and explicit scope.
- [ ] Validate README, docs index, deployment/runtime instructions, and frontend handoff.
- [ ] Run link/reference checks and documentation CI; review the diff before merge.

This report is deliberately honest about coverage: current canonical references and active handoff documents have been reconciled; historical snapshots remain dated evidence rather than being rewritten as current truth. The API v1 reference is not an exhaustive route/DTO catalogue, and this report is **not** a historical-evidence re-run, security certification, or production-readiness approval.
