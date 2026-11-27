# Documentation Content Audit — 2026-11-27

**Scope:** Documentation branch `docs/backend-handoff-and-documentation-refresh`, compared against the backend checkpoint `148177826c5eebd56b6134851e5d901809549448`.
**Purpose:** Make active engineering documentation accurate, navigable, and useful for frontend work and the next backend maintenance cycle.
**Disposition:** This is a focused current-state audit. It is not a claim that every historical document or every implementation detail has been independently reverified.

## Documentation classes

1. **Active reference** — README, runtime boundary, API reference, current architecture/security contracts, deployment, testing, handoff, and roadmap. These must describe current behavior and clearly mark unverified details.
2. **Current audit/review evidence** — dated security, tenant isolation, architecture and QA reviews. Preserve the date, scope, exact checkpoint, and evidence type; do not silently convert a limited review into a global conclusion.
3. **Historical baseline/design** — earlier-stage audits, proposals, implementation plans, benchmark evidence, and frozen schema material. Preserve as history where useful, but add a visible historical/superseded status and link to current reference when a reader could mistake it for current behavior.

## Verified checkpoint facts

- Java 21; Spring Boot 3.5.16; MySQL 8.4; Flyway 11.20.3.
- WMS runtime entry point: `WmsRuntimeApplication`; Maven profile `wms-runtime`; Spring profile `wms`.
- Dedicated runtime database `zirologistics_wms`; migration location `classpath:db/wms-migration`; migration set V1–V32. Applied migrations are immutable; future changes are additive from V33.
- API prefix: `/api/wms/v1`. Existing endpoint-specific response shapes and successful status conventions are compatibility constraints.
- Backend is paused temporarily for frontend foundation work; this is not a security completion or production-ready declaration.

## Current security and integration boundaries

- Tenant and platform JWT trust domains are separate. Cross-signature rejection is tested using real RSA keys/decoders.
- Tenant credential-version enforcement and MFA persistence foundations are merged. Tenant MFA orchestration (enrollment, verification, step-up, recovery and transactional one-time proof consumption) is incomplete and disabled by default.
- Platform identity/security primitives exist, but a controlled initial Platform Administrator provisioning process and a complete platform HTTP surface are not established by the reviewed checkpoint.
- Support-grant governance exists; a runtime scoped access consumer/enforcement path was not identified in the reviewed source slice.
- Selected tenant isolation source reviews and write-predicate fixes are evidence for those reviewed paths only. Complete route-to-query verification, nested/bulk foreign-ID negative tests, concurrency, audit rollback and worker/command context checks remain open.
- Frontend can begin with synthetic/mock repositories. Real account provisioning and privileged integration are deferred until the relevant backend gates are reviewed and approved.

## Content audit findings and actions

| Document group | Audit disposition |
|---|---|
| README and docs index | Refresh checkpoint, status, module overview, safe setup pointers and active-document links. Keep capability claims qualified. |
| API reference and API contract audits | Remove stage/work-package framing from active entry points. Treat route inventory, validation, response shape, status code, pagination and temporal behavior as compatibility-sensitive. Clearly flag timezone interpretation of `LocalDateTime` scheduling fields as unresolved. |
| Runtime/deployment/database | Align migration count and safety boundary with V1–V32/V33+, dedicated database, supported runtime and environment configuration. Never point WMS migrations at legacy databases. |
| Security architecture and contracts | Separate implemented foundations from incomplete orchestration/enforcement. Do not present design contracts as complete implementations. |
| Dated security/source reviews | Retain dated scope and evidence. Add current checkpoint references; avoid rewriting historical results. |
| Testing/QA | Label source-reviewed, unit-tested, HTTP-tested, MySQL integration-tested, CI-verified, partially verified and unverified evidence precisely. |
| Roadmap/handoff | Keep these the single current navigation points for the paused backend and ordered resume work. |

## Known stale or potentially misleading material

- Active API documents still contain Stage 13 / WP labels, historical baseline SHAs, and old cross-links; they must not be presented as current implementation summaries without a current-state note.
- Some architecture documents retain old framework baselines or proposed/in-progress status. A document-specific status and baseline should be preserved if it is historical; otherwise it needs reconciliation against current source.
- Old QA snapshots describe PRs and CI states from their capture date. They are historical evidence and cannot be used as the latest overall CI status.
- Stage/work-package terms may remain in historical body text where needed for traceability. Active filenames and navigation should use subject-based names.

## Frontend integration rules

1. Build UI flows against the documented API and permission model, but keep mock data/repositories replaceable.
2. Do not infer that a module listed in the backend means every workflow or UI edge case is complete.
3. Do not assume tenant MFA, support access, platform-admin bootstrap, or production operations are complete.
4. Treat tenant-local scheduling semantics as unresolved for inbound expected-at, outbound scheduled-at, and transfer scheduled-at until a backend contract decision is recorded.
5. Preserve v1 endpoint-specific payloads and success codes. Avoid inventing a universal response envelope.
6. Keep mock and real API adapters separate so switching to backend does not require rewriting screens or workflow state.

## Audit completion limitations

This refresh establishes the checkpoint and updates selected active references. It does **not** constitute a line-by-line review of all 89 Markdown files, a full route-to-query security audit, or a production readiness review. Historical documents should be reconciled in targeted slices when their content is needed or backend work resumes.

## Recommended document maintenance

- Keep `docs/README.md`, README, the backend maintenance handoff, and the backend improvement roadmap current.
- Use descriptive lowercase kebab-case names for newly created active docs.
- Include date/checkpoint, status, scope, and evidence type in current audits.
- Link historical baselines to their current successor instead of deleting useful evidence.
- Validate relative links whenever files are renamed.
