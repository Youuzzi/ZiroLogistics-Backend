# Stage 16.5 — Tenant Isolation Test Gap Audit

**Status:** Source-level audit; no runtime execution performed in this audit.
**Scope:** Existing tenant lifecycle, tenant provisioning, tenant profile integration tests, and tenant-profile HTTP boundary test.

## Reviewed evidence

- `WmsTenantLifecycleMySqlIntegrationTest`: exercises suspend/reactivate/close transitions, idempotent suspend, invalid direct close, invalid operation reference, lifecycle versioning, and audit-event assertions. It invokes the lifecycle service directly; it is not an HTTP test.
- `WmsTenantProvisioningMySqlIntegrationTest`: exercises new-owner provisioning, existing verified global user membership, rejects unverified owners and disabled provisioning, verifies role/permission assignments, audit operation reference, idempotency and conflicting repeated configuration. Service-level integration test.
- `WmsTenantProfileMySqlIntegrationTest`: exercises profile update and audit, stale version conflict, missing permission, forged principal pointing at a different tenant, invalid timezone and no-write assertions. Service-level integration test.
- `WmsTenantProfileControllerHttpTest`: exercises unauthenticated HTTP request (401), authenticated GET and PUT request mapping, optimistic version mapping, and invalid currency request rejection before service invocation. HTTP test uses mocked service and does not verify DB tenant isolation.
- `WmsRuntimeBoundaryIntegrationTest` and `WmsTenantIdentityPersistenceIntegrationTest` were identified for further targeted review; their detailed assertions are not recorded here.

## Findings

1. Tenant profile service tests include a forged cross-tenant principal case, but this is narrow coverage of the tenant-profile service, not proof for all WMS resources.
2. Lifecycle and provisioning tests provide meaningful transactional/database evidence, but do not test HTTP authorization boundaries.
3. The tenant-profile HTTP test proves route authentication, mapping and request validation with a mocked service; it does not test persistence or cross-tenant data access.
4. Based on this reviewed subset, the audit cannot verify that all domain controllers/services consistently enforce tenant ownership for IDs supplied by clients.

## Recommended follow-up tests (not yet implemented)

- Resource ownership tests for tenant A requesting tenant B warehouse/location IDs via both read and write endpoints.
- Cross-tenant document/inventory resource ID manipulation tests for representative inbound/outbound/inventory workflows.
- Assert denial response and unchanged database state for rejected writes.
- Where feasible, HTTP tests that use real service/repository wiring and isolated MySQL/Testcontainers rather than only mocked service calls.

## Evidence limitations

This is a source-level audit report. It does not claim a tenant-isolation vulnerability was found. It also does not claim complete tenant isolation verification. No new tests were added or executed in this commit. Runtime evidence requires implementing the follow-up tests and running the repository CI.
