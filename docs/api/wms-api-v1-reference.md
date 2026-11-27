# ZiroLogistics WMS v1 API Reference

**API prefix:** `/api/wms/v1`  
**Checkpoint:** Backend documentation refresh dated 2026-11-27.  
**Status:** Compatibility-oriented reference. Endpoint families are inventoried, but this document does not claim that every workflow is fully implemented, end-to-end verified, or production-ready.

## Runtime and API boundary

The WMS application is the supported backend runtime. HTTP controller registration depends on runtime security configuration. The frontend must not assume routes are anonymously available or that route existence implies deployment enablement.

The API is tenant-scoped. Tenant identity is resolved from the authenticated principal and active membership, not from a client-supplied tenant ID. Frontend route guards and hidden controls are UX only; server-side authentication and permission checks remain authoritative.

## Endpoint families

| Area | Route family |
|---|---|
| Authentication | `/auth` |
| Invitations | `/invitations` |
| Tenant profile | `/tenants/current/profile` |
| Roles and permissions | `/tenants/current/roles`, `/permissions`, `/memberships/{membershipPublicId}/roles` |
| Warehouse/location | `/tenants/current/warehouses` |
| Item master data | `/tenants/current/items`, `/item-categories`, `/uoms` |
| Inventory | `/tenants/current/inventory` |
| Inbound/putaway | `/tenants/current/inbound-receipts`, `/putaway-tasks` |
| Outbound/fulfillment | `/tenants/current/outbound-shipments`, `/picking-tasks`, `/packing-sessions`, `/shipping` |
| Transfers | `/tenants/current/stock-transfers` |
| Inventory control | `/tenants/current/stock-counts`, `/inventory-adjustments` |
| Replenishment | `/tenants/current/replenishment-rules`, `/replenishment-tasks` |
| Reporting | `/tenants/current/reports/operational-summary` |

Use the controller inventory and endpoint-specific contracts when implementing a particular screen. The family list is a navigation aid, not a complete method/path/schema listing.

## Authentication and access

The tenant authentication flow uses credential verification followed by tenant selection and issuance of a tenant-bound access token. Protected routes require an authenticated tenant context and persisted permissions.

The current anonymous POST allowlist, when anonymous authentication routes are enabled by configuration, is limited to:
- `POST /auth/credentials`
- `POST /auth/tenant-selection`
- `POST /auth/password-reset-requests`
- `POST /auth/password-resets`
- `POST /auth/email-verification-requests`
- `POST /auth/email-verifications`
- `POST /invitations/acceptances`

This list is relative to the API prefix above. Invitation issuance and operational routes require authentication and authorization. Configuration defaults are restrictive; consult the runtime config and deployment guide before assuming a route is enabled.

## Authorization and tenant boundaries

- Effective permission state is resolved server-side from persisted membership, role and permission records.
- Tenant IDs must not be trusted from request bodies or client state.
- Public resource IDs must be resolved within the authenticated tenant.
- Tenant Owner is not automatically granted inventory-posting rights.
- Never use frontend permission checks as a substitute for API authorization.

## Request conventions

- Public identifiers are used for client-facing resource references; internal database IDs are not API identifiers.
- Quantities use decimal semantics and may be validated with domain-specific precision. Do not convert inventory quantities to JavaScript binary floating-point for arithmetic; use decimal-safe handling.
- Some workflow commands require expected version values for optimistic concurrency.
- Commands with idempotency guarantees use command-specific request identifiers. POST alone does not imply idempotency.
- Request bodies are validated at the controller boundary where source inspection confirms `@Valid`; always handle server validation errors in the UI.

## Response compatibility

The v1 API does not use one universal response envelope. Responses include endpoint-specific objects, maps, lists, simple lists, and no-content results. Most successful controller operations return HTTP 200, including some POST commands; selected identity operations return 204.

Preserve existing keys, shapes, and success status codes. Do not assume all creates return 201, or wrap every result in `data/meta/error`. API compatibility changes require a separately reviewed decision.

## Error handling

The WMS error contract provides machine-readable codes and generic messages. The current convention includes:
- 400 for invalid request/validation input
- 401 for unauthenticated requests
- 403 for authorization denial
- 404 for confirmed missing tenant-scoped resources
- 409 for workflow, version, or integrity conflicts
- 500 for unexpected server failures with redacted public messages

Implement UI behavior from the actual endpoint contract and error code, not solely from status codes. Never display internal exception messages as user-facing text.

## Pagination and list bounds

Collection bounds vary by endpoint. Inventory balance and ledger endpoints use page/size bounds; selected transfer and role collections have bounded pagination; other workflow list reads use fixed limits where present. Do not assume every list shares the same query or response shape. Confirm the endpoint-specific contract before building infinite scroll, pagination controls, or filters.

## Scheduling and time-zone limitation

Inbound expected-at, outbound scheduled-at, and transfer scheduled-at fields currently use `LocalDateTime` in the inspected contracts. This type carries no offset or timezone. The tenant-local interpretation, DST gap/overlap policy, and conversion/storage semantics are not yet finalized.

**Frontend must not silently treat these values as UTC instants.** Scheduling UI integration is blocked on an explicit backend contract decision or a clearly agreed temporary interpretation recorded with the implementation.

## Modules listed are not full workflow certification

Warehouse, item/UOM, inventory, receiving, putaway, transfers, stock count, adjustments, replenishment, picking, packing, shipping and reports are present in the backend domain/controller inventory. A listed module does not mean all workflow branches, exception paths, authorization combinations, UI states, or operational controls are complete.

## Verification boundaries

The current backend has selected source reviews, unit and HTTP tests, MySQL integration tests, and PR CI evidence. Their scopes differ. This reference is not a complete route-to-query verification, an exhaustive serialization contract, a penetration-test report, or a production-readiness declaration.

## Frontend integration approach

Use synthetic data initially and separate mock repositories from API adapters. Keep UI state and workflows decoupled from transport so mock-to-API replacement does not require redesign. For each integrated endpoint, record the method/path, permission, request DTO, response shape, errors, pagination, concurrency/version requirements, and test evidence in an endpoint-specific integration note.
