# Backend Documentation

Documentation is organized by purpose: current reference, architecture/design, audits/evidence, runtime/deployment, security, testing, and historical material.

## Current entry points
- [Backend maintenance handoff](project/backend-maintenance-handoff-2026-11-27.md) — current pause checkpoint and resume order.
- [Backend improvement roadmap](roadmap/backend-improvement-roadmap.md) — prioritized backend work after frontend foundation.
- [Documentation content audit](audits/documentation-content-audit-2026-11-27.md) — naming and content findings, audit scope, and remaining cleanup.
- [Runtime boundary](runtime/WMS_RUNTIME_BOUNDARY.md) — application runtime, database target, migration safety, configuration.
- [Local Docker deployment](deployment/WMS_LOCAL_DOCKER_DEPLOYMENT.md) — isolated local compose workflow.
- [Controlled platform bootstrap](architecture/WMS_CONTROLLED_PLATFORM_BOOTSTRAP.md) — bootstrap constraints and behavior.
- [Tenant MFA contract](security/tenant-mfa-and-step-up-authentication-contract.md) — persistence foundations and incomplete orchestration.
- [Tenant isolation and authorization](security/TENANT_ISOLATION_AND_AUTHORIZATION.md) — data access and authorization principles.

## API references
- [WMS API v1 reference](api/wms-api-v1-reference.md)
- [Inventory endpoint baseline](api/inventory-endpoint-baseline.md)
- [HTTP API semantics](api/http-api-semantics.md)
- [API request/response contract](api/api-request-response-contract.md)

## Architecture and design
Architecture references cover identity, tenant context, authorization, role governance, warehouse operations, inventory posting, concurrency, and platform administration. The directory includes implemented references, contracts, designs, and plans; read each document's status and checkpoint before treating it as current behavior.

## Audits and verification
- [Security and QA baseline](audits/security-qa-baseline.md)
- [Security and QA verification status](audits/security-qa-closure-criteria.md)
- [Platform control-plane review](audits/platform-control-plane-review-2026-11-27.md)
- [Tenant isolation review](audits/tenant-isolation-review-2026-11-27.md)
- [Outbound tenant-isolation review](audits/outbound-tenant-isolation-review-2026-11-27.md)
- [Security coverage inventory](audits/security-coverage-inventory-2026-11-27.md)
- [Documentation content audit](audits/documentation-content-audit-2026-11-27.md)

Audits are dated and scoped evidence, not certifications or claims that every code path was reviewed.

## Database and testing
- `database/source/` contains historical schema reference material, not executable migrations.
- `testing/` contains HTTP contract and regression test references.
- WMS Flyway migrations are in `src/main/resources/db/wms-migration`; applied migrations are immutable.

## Naming convention
Preferred filenames for active documents: descriptive lowercase kebab-case. The existing tree has not yet been fully normalized; see the content audit for examples and remaining cleanup. Historical stage/work-package identifiers may remain in document text when needed for traceability, but should not be used as active navigation labels.
