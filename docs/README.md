# Backend Documentation

Documentation is organized by purpose: current reference, architecture/design, audits/evidence, runtime/deployment, security, testing, and historical material.

## Current entry points
- [Backend maintenance handoff](project/backend-maintenance-handoff-2026-11-27.md) — current pause checkpoint and resume order.
- [Backend improvement roadmap](roadmap/backend-improvement-roadmap.md) — prioritized backend work to resume after documentation cleanup and frontend foundation.
- [Documentation content audit](audits/documentation-content-audit-2026-11-27.md) — repository-wide naming, title, content, and verification findings.
- [Runtime boundary](runtime/wms-runtime-boundary.md) — application runtime, database target, migration safety, configuration.
- [Local Docker deployment](deployment/wms-local-docker-deployment.md) — isolated local compose workflow.
- [Controlled platform bootstrap](architecture/wms-controlled-platform-bootstrap.md) — bootstrap constraints and behavior.
- [Tenant MFA contract](security/tenant-mfa-and-step-up-authentication-contract.md) — persistence foundations and incomplete orchestration.
- [Tenant isolation and authorization](security/tenant-isolation-and-authorization.md) — data access and authorization principles.

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
All Markdown document filenames now use descriptive lowercase kebab-case, except conventional `README.md` files. Document titles have also been normalized to describe their subject rather than a delivery stage or work package. Historical stage/work-package identifiers may remain in body text where they preserve evidence provenance, but they are not used as active filenames or document titles.

## Canonical documentation ownership

Use these documents as the primary reference for each subject. Supporting audits and historical designs provide evidence or rationale; they do not override the current canonical contract.

| Subject | Primary reference | Supporting material |
|---|---|---|
| Product/runtime overview | [Backend README](../README.md) and [Runtime Boundary](runtime/wms-runtime-boundary.md) | [Local Docker Deployment](deployment/wms-local-docker-deployment.md) |
| WMS v1 API behavior | [WMS API v1 Reference](api/wms-api-v1-reference.md) | [HTTP Semantics](api/http-api-semantics.md), [Request/Response Contract](api/api-request-response-contract.md), [Endpoint Inventory](api/inventory-endpoint-baseline.md) |
| Tenant authentication HTTP contract | [Authentication HTTP API Contract](architecture/wms-authentication-http-api-contract.md) | [Authentication and Token Architecture](architecture/wms-authentication-and-token-architecture.md) is historical architecture context |
| Tenant identity and lifecycle | [Tenant Identity Foundation](architecture/wms-tenant-identity-foundation.md) | [Tenant Organization Management](architecture/wms-tenant-organization-management.md), [Controlled Bootstrap](architecture/wms-controlled-platform-bootstrap.md) |
| Tenant roles and permissions | [Tenant Role Templates and Permission Governance](architecture/tenant-role-templates-and-permission-governance-policy.md) | [Role-Based Access Control](architecture/role-based-access-control.md), [Role Management Design](architecture/wms-role-permission-management-design.md), dated RBAC audits |
| Platform administration | [Platform Administration and Operations](architecture/platform-administration-and-operations.md) | [Platform Authentication Contract](architecture/platform-authentication-contract.md), [Platform Authorization Contract](architecture/platform-authorization-contract.md), [Platform Control-Plane Review](audits/platform-control-plane-review-2026-11-27.md) |
| Tenant isolation | [Tenant Isolation and Authorization](security/tenant-isolation-and-authorization.md) | [Route-to-Query Verification Plan](audits/tenant-isolation-route-to-query-verification-plan.md), dated source-review slices |
| Tenant MFA | [Tenant MFA and Step-Up Authentication Contract](security/tenant-mfa-and-step-up-authentication-contract.md) | MFA persistence and test evidence referenced by that contract |
| Warehouse and inventory | [Warehouse Operations Architecture](architecture/wms-warehouse-operations-architecture.md) | [Warehouse Master Data](architecture/wms-warehouse-master-data-design.md), [Inventory Core](architecture/wms-inventory-core-architecture.md), [Inventory Posting](architecture/wms-inventory-posting-core-design.md), [Inventory Control](architecture/inventory-control-architecture.md) |
| Locale and temporal behavior | [Tenant Locale and Temporal Data Policy Audit](audits/tenant-locale-and-temporal-data-policy-audit.md) | [Temporal Field and Worker Inventory](audits/temporal-field-and-worker-inventory.md), [Temporal Consistency Plan](audits/temporal-consistency-implementation-plan.md) |
| Security and QA status | [Security and QA Baseline](audits/security-qa-baseline.md) and [Security/QA Verification Status](audits/security-qa-closure-criteria.md) | Focused security reviews, coverage inventories, and DAST plan |
| Database compatibility | [Runtime Boundary](runtime/wms-runtime-boundary.md) | [WMS V1 Schema Baseline](architecture/wms-v1-schema-implementation-baseline.md), [Flyway/MySQL Compatibility](architecture/wms-flyway-mysql-84-compatibility.md), migration-specific audits |
| Backend continuation | [Backend Maintenance Handoff](project/backend-maintenance-handoff-2026-11-27.md) and [Backend Improvement Roadmap](roadmap/backend-improvement-roadmap.md) | This index and the documentation content audit |
