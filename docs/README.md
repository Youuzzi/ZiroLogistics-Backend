# Backend Documentation

This directory contains active engineering references and historical verification records for the WMS backend.

## Start here

- [Backend Maintenance Handoff](project/backend-maintenance-handoff-2026-11-27.md) — exact engineering checkpoint, completed changes, security boundaries, and resume instructions.
- [Backend Improvement Roadmap](roadmap/backend-improvement-roadmap.md) — prioritized implementation and verification backlog.
- [Documentation Content Audit](audits/documentation-content-audit-2026-11-27.md) — audit scope, current checkpoint facts, stale-content findings, and frontend integration boundaries.
- [Backend Runtime Boundary](runtime/WMS_RUNTIME_BOUNDARY.md) — supported application runtime, database targets, migration safety, and configuration.
- [Local Docker Deployment](deployment/WMS_LOCAL_DOCKER_DEPLOYMENT.md) — isolated local Compose workflow.
- [Controlled Platform Bootstrap](architecture/WMS_CONTROLLED_PLATFORM_BOOTSTRAP.md) — one-time tenant-owner bootstrap constraints.
- [Tenant MFA and Step-Up Contract](security/tenant-mfa-and-step-up-authentication-contract.md) — MFA architecture, implemented persistence slices, and enablement requirements.
- [Tenant Isolation and Authorization](security/TENANT_ISOLATION_AND_AUTHORIZATION.md) — tenant data-access and authorization principles.

## API

- [WMS API v1 Reference](api/wms-api-v1-reference.md)
- [Inventory Endpoint Baseline](api/inventory-endpoint-baseline.md)
- [HTTP API Semantics](api/http-api-semantics.md)
- [API Request/Response Contract](api/api-request-response-contract.md)

## Architecture

Architecture references cover identity, tenant context, authorization, role governance, warehouse operations, inventory posting, concurrency, and platform administration. See the files in [architecture](architecture/).

## Audits and verification

- [Security and QA Baseline](audits/security-qa-baseline.md)
- [Security and QA Verification Status](audits/security-qa-closure-criteria.md)
- [Documentation Content Audit](audits/documentation-content-audit-2026-11-27.md)
- [Platform Control-Plane Review](audits/platform-control-plane-review-2026-11-27.md)
- [Tenant Isolation Review](audits/tenant-isolation-review-2026-11-27.md)
- [Outbound Tenant-Isolation Review](audits/outbound-tenant-isolation-review-2026-11-27.md)
- [Security Coverage Inventory](audits/security-coverage-inventory-2026-11-27.md)

Source reviews describe only the code paths they inspected. They are not full penetration tests or security certifications.

## Database and testing

- `database/source/` contains historical reference material only. It is not an executable migration source.
- `testing/` contains HTTP contract and regression test references.
- WMS Flyway migrations live in `src/main/resources/db/wms-migration`; applied migrations are immutable.

## Naming convention

Active documentation uses descriptive, lowercase, hyphen-separated filenames. Historical references may mention former project work packages in their body text, but filenames should describe the document's subject rather than a stage number.
