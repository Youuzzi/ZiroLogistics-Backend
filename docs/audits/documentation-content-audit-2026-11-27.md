# Documentation Content Audit — 2026-11-27

**Target:** `docs/backend-handoff-and-documentation-refresh`  
**Code checkpoint:** `main` at `148177826c5eebd56b6134851e5d901809549448`  
**Scope of this pass:** repository-wide documentation tree/name/link scan; current-reference reconciliation against source, configuration, migrations, and tests for runtime/deployment, API compatibility, tenant identity/RBAC, tenant isolation, MFA, platform control plane, temporal behavior, security/QA, and the frontend handoff. Historical evidence is retained with its original baseline and explicit status rather than rewritten as current truth.  
**Status:** Current reference and handoff reconciliation completed. Historical findings have not all been independently re-run, and this is not an endpoint-by-endpoint API or production-security certification.

## Executive disposition

PR #367 refreshes current-reference content; PR #368 completes repository-wide filename, title, and link standardization. The documentation tree contains active references, dated audits, historical baselines, design proposals, plans, test inventories, and source schema artifacts. These materials are classified by purpose and evidence status rather than rewritten as though they describe one current implementation.

## Repository-wide filename and title findings

- All 93 Markdown files were included in the repository-wide scan.
- The 51 nonstandard/non-descriptive Markdown filenames were renamed to descriptive lowercase kebab-case; conventional `README.md` filenames were retained.
- The final tree scan found no remaining uppercase/underscore Markdown filenames and no stage/work-package identifiers in Markdown filenames.
- The 32 document H1 titles and identified delivery-stage/work-package headings were normalized.
- Six stale relative Markdown links were corrected; the final repository-wide relative-link scan reported zero broken links.
- Historical identifiers may remain in body text where they preserve evidence provenance; they are not used as active filenames or document titles.

## Current checkpoint facts used by the handoff

- Java 21, Spring Boot 3.5.16, MySQL 8.4, Flyway 11.20.3.
- WMS runtime entry point: `WmsRuntimeApplication`; Maven profile `wms-runtime`; Spring profile `wms`.
- Dedicated runtime database: `zirologistics_wms`; migrations V1–V32. Applied migrations are immutable; future changes are additive from V33.
- API prefix: `/api/wms/v1`. Existing endpoint-specific response shapes and successful status conventions are compatibility constraints.
- Platform control-plane foundations now include V29 operator/role/permission/MFA persistence, V30 support-grant governance, platform JWT and permission services, role administration, tenant-lifecycle authorization, and support-grant administration. End-to-end platform MFA/login HTTP, initial Platform Administrator provisioning, platform controllers, and support-grant scope consumption remain incomplete.
- Backend feature work remains paused while documentation cleanup is reviewed and merged. Frontend implementation is deferred until PR #367 and PR #368 are reviewed, validated, and merged. This is not security completion or production-readiness certification.

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

## Remaining follow-up work outside this documentation pass

1. Historical audits and design snapshots preserve their original source checkpoints and have not all been independently re-run. They are labelled as historical, preliminary, proposed, or partial where that distinction changes how readers should use them; do not treat their findings as current truth without revalidation.
2. The WMS API v1 reference is explicitly compatibility-oriented, not an exhaustive endpoint/DTO catalogue. Full route-to-controller-to-DTO/validation/status/pagination verification remains a backend follow-up in the roadmap.
3. Security and production readiness are not certified. Tenant MFA orchestration, first Platform Administrator provisioning, support-grant scope consumption, complete tenant-isolation verification, and production operations remain open as documented.

## Frontend handoff guardrails

- Frontend implementation is deferred until PR #367 and PR #368 are reviewed and merged.
- Once the documentation handoff is accepted, use synthetic/mock data behind repository/service interfaces, not directly embedded in screens.
- Keep API adapters separate so mock-to-real integration does not require screen rewrites.
- Do not assume tenant MFA, support access, Platform Administrator bootstrap, or production operations are complete.
- Tenant-local interpretation of scheduling fields remains a backend contract issue; do not invent timezone semantics in frontend.
- Preserve endpoint-specific v1 payload and success-code behavior once integration begins.

## Completion criteria for this documentation pass

- [x] Normalize all 51 non-descriptive/nonstandard Markdown filenames to descriptive lowercase kebab-case; retain conventional `README.md` names.
- [x] Normalize the 32 delivery-stage/work-package H1 titles and the delivery-stage section headings found by the repository-wide heading scan.
- [x] Update inbound references and canonical navigation links for the renamed documents.
- [x] Check relative Markdown links across all 93 Markdown files; six stale links were corrected and the final recheck found zero broken Markdown links.
- [x] Establish canonical documentation ownership by subject in `docs/README.md`; supporting designs and audits are classified as evidence or historical context.
- [x] Reconcile current canonical references against source/configuration/migrations/tests for runtime, deployment, API compatibility scope, tenant identity/RBAC, tenant isolation, MFA, platform control plane, temporal behavior, security/QA, and the frontend handoff.
- [x] State explicitly that the API v1 reference is compatibility-oriented and track full route/DTO/status/pagination verification as a separate backend roadmap item.
- [x] Reconcile security claims with implementation primitives, available negative tests, CI evidence, and explicit partial-verification boundaries.
- [x] Align README, docs index, runtime/deployment guidance, roadmap, and handoff with the documentation-first decision.
- [x] Confirm PR #367 Backend CI, Authenticated WMS API DAST, and CodeQL pass on its current head.
- [ ] Merge PR #367, retarget PR #368 to `main`, run its required checks, and review the final stacked diff before merging.

This two-PR documentation pass closes repository-wide Markdown naming, title, navigation, current-reference, and handoff cleanup once both PRs are merged. It does **not** replace the separate endpoint-by-endpoint API inventory, re-run every historical audit, certify tenant isolation, or approve production readiness.
