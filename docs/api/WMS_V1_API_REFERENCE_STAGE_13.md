# ZiroLogistics WMS v1 API Reference

**API version:** `v1`
**Base path:** `/api/wms/v1`
**Status:** Stage 13 curated reference. The route inventory is source-derived; endpoint authorization, request/response schemas and runtime behavior are described only where verified.

## 1. Versioning and compatibility

- The existing API prefix `/api/wms/v1` is retained.
- This Stage 13 effort completes and hardens the existing API; it does not introduce a second version or new business workflows.
- Existing v1 request field names, response shapes and successful status codes are preserved unless a concrete defect requires a narrowly scoped correction.
- Breaking changes require an explicit versioning decision. Do not silently change v1 response envelopes or replace every map response with DTOs.
- Route declarations are not proof that a route is enabled in a deployment. Runtime registration and security gates are defined below.

## 2. Route inventory

The complete controller route list is maintained in [WMS v1 endpoint inventory baseline](WMS_V1_ENDPOINT_INVENTORY_STAGE_13_BASELINE.md). That file records the original Stage 13 baseline and is retained as historical evidence. WP2 subsequently corrected the controller-registration behavior as described below. WP4 adds bounded pagination to the remaining unbounded transfer and role collection reads.

Primary route families:

| Domain | Base route |
|---|---|
| Authentication | `/auth` |
| Invitations | `/invitations` |
| Current tenant profile | `/tenants/current/profile` |
| Roles and permissions | `/tenants/current/roles`, `/permissions`, `/memberships/{membershipPublicId}/roles` |
| Warehouses and locations | `/tenants/current/warehouses` |
| Inventory master data | `/tenants/current/items`, `/item-categories`, `/uoms` |
| Inventory | `/tenants/current/inventory` |
| Inbound and putaway | `/tenants/current/inbound-receipts`, `/putaway-tasks` |
| Outbound and fulfilment | `/tenants/current/outbound-shipments`, `/picking-tasks`, `/packing-sessions`, `/shipping` |
| Transfers | `/tenants/current/stock-transfers` |
| Inventory control | `/tenants/current/stock-counts`, `/inventory-adjustments` |
| Replenishment | `/tenants/current/replenishment-rules`, `/replenishment-tasks` |
| Reporting | `/tenants/current/reports/operational-summary` |

## 3. Runtime registration and security matrix

### Registration gates

| Configuration | Result |
|---|---|
| `wms.security.jwt.enabled=false` or missing | WMS HTTP controllers are not registered; the WMS HTTP security chain denies all requests. |
| `wms.security.jwt.enabled=true` | WMS HTTP controllers are registered and all routes require authentication by default. |
| JWT enabled + `wms.security.http.auth-endpoints-enabled=false` | Protected operational and identity routes remain registered; no anonymous authentication POST routes are permitted. |
| JWT enabled + `wms.security.http.auth-endpoints-enabled=true` | Only the exact anonymous POST allowlist below is permitted; all other routes remain authenticated. |

### Exact anonymous POST allowlist

| Method | Route |
|---|---|
| POST | `/api/wms/v1/auth/credentials` |
| POST | `/api/wms/v1/auth/tenant-selection` |
| POST | `/api/wms/v1/auth/password-reset-requests` |
| POST | `/api/wms/v1/auth/password-resets` |
| POST | `/api/wms/v1/auth/email-verification-requests` |
| POST | `/api/wms/v1/auth/email-verifications` |
| POST | `/api/wms/v1/invitations/acceptances` |

Invitation issuance, existing-account invitation acceptance, password changes and every operational route require an authenticated WMS access token. Invitation issuance also requires the persisted tenant membership and invitation permission. See [WMS authentication and account lifecycle HTTP contract](../architecture/WMS_AUTHENTICATION_HTTP_API_CONTRACT.md).

### Authorization model

- Tenant identity comes from the authenticated principal and the current persisted active membership, never from a request-supplied tenant ID.
- Effective permissions are resolved from persisted membership-role-permission state at the application boundary.
- Public resource IDs (warehouse, item, location, shipment, transfer, membership, etc.) select records only within the resolved tenant.
- Frontend route guards and hidden controls are not security boundaries.

## 4. Request validation and field conventions

- Every inspected `@RequestBody` parameter in the 19-controller inventory is annotated with `@Valid`.
- Public identifiers are used at the API boundary; internal database primary keys are not intended as client identifiers.
- Quantities use decimal types and domain-specific precision validation; do not coerce them to floating-point values in clients.
- Item master weight metadata is optional but must be supplied as the pair `weightPerBaseUom` and `weightUomCode`; the UOM must be an active MASS unit and weight is expressed per item base UOM.
- A warehouse location may define `maxWeightCapacity` with `weightUom`; the UOM must be an active MASS unit. When configured, every positive inventory posting into that location is checked against projected on-hand weight. Items already held in a capacity-controlled location must have weight metadata before further stock can be added.
- Versioned workflow commands carry expected version values where required.
- Idempotent commands use command-specific request identifiers. A POST is not automatically idempotent.
- Some scheduled date-time fields use `LocalDateTime`, which contains no timezone/offset. Their business-local interpretation remains an explicit contract item to settle before frontend integration.

## 5. Collection bounds

| Collection | Default | Allowed range | Notes |
|---|---:|---:|---|
| Inventory balances | Existing endpoint default | Page size 1–200 | Deterministic ordering and tenant-scoped query. |
| Inventory ledger | Existing endpoint default | Page size 1–200 | Deterministic ordering and tenant-scoped query. |
| Stock transfers | Page 0, size 200 | Page >= 0, size 1–200 | WP4; response remains a list. |
| Tenant roles | Page 0, size 200 | Page >= 0, size 1–200 | WP4; response remains a list. |
| Membership roles | Page 0, size 200 | Page >= 0, size 1–200 | WP4; response remains a list. |

Other workflow list endpoints inspected at baseline already apply fixed `LIMIT 200` where applicable. Avoid removing these bounds. If a route requires page metadata or filters, introduce that as a deliberate response-contract change rather than changing the current list shape silently.

## 6. Response and HTTP status conventions

- WMS v1 currently returns endpoint-specific records, maps, lists of maps, simple lists or no-content responses; there is no universal response envelope.
- Most successful controller methods return HTTP 200, including many POST commands. Selected identity operations explicitly return 204 when there is no response body.
- Preserve these successful status codes for v1 unless a separately reviewed compatibility decision authorizes a change.
- The WMS error contract uses stable machine-readable codes and generic public messages. See [Stage 13 request/response contract audit](WMS_V1_REQUEST_RESPONSE_CONTRACT_STAGE_13.md) and [Stage 13 HTTP semantics audit](WMS_V1_HTTP_SEMANTICS_STAGE_13.md).

## 7. Error contract

The WP3 WMS exception advice takes precedence for WMS routes and returns a safe JSON error body:

```json
{
  "code": "INVALID_REQUEST",
  "message": "The request is invalid"
}
```

The `code` and HTTP status depend on the failure category. Internal exception messages, SQL details, database names, credentials and identifiers must not be exposed. The verified categories include invalid request (400), authentication failure (401), access denial (403), confirmed missing tenant-scoped resource (404, `RESOURCE_NOT_FOUND`), request conflict (409) and unexpected internal failure (500). WP7 extends typed 404 handling across the audited workflow and master-data service lookups while preserving validation, authorization and workflow-state semantics.

## 8. Operational limits

- The inspected 19 WMS controllers contain no multipart upload endpoint.
- WMS profile properties do not explicitly define global servlet request-size or multipart limits. Do not infer an unlimited request body; deployment/container defaults may apply. A deliberate payload budget remains a deployment-readiness item.
- Request bodies for credentials, action tokens and password lifecycle routes must not be logged.
- CORS origins, trusted proxy CIDRs, JWT key material, database credentials and email secrets are deployment configuration, not source-code defaults.

## 9. Supporting contracts and audit evidence

- [Authentication and account lifecycle HTTP contract](../architecture/WMS_AUTHENTICATION_HTTP_API_CONTRACT.md)
- [Stage 13 architecture and delivery contract](../architecture/WMS_BACKEND_API_COMPLETION_STAGE_13_ARCHITECTURE.md)
- [Stage 13 endpoint inventory baseline](WMS_V1_ENDPOINT_INVENTORY_STAGE_13_BASELINE.md)
- [Stage 13 request/response contract audit](WMS_V1_REQUEST_RESPONSE_CONTRACT_STAGE_13.md)
- [Stage 13 tenant/permission boundary audit](../security/WMS_V1_TENANT_PERMISSION_COMMAND_BOUNDARY_STAGE_13.md)
- [Stage 13 HTTP semantics and operational limits audit](WMS_V1_HTTP_SEMANTICS_STAGE_13.md)

## 10. Frontend integration gate

Do not treat the API as frontend-ready solely because the routes exist. Before frontend implementation, WP9/WP10 must verify representative authenticated HTTP calls, request validation, response JSON shapes, pagination, error codes, cross-tenant negative cases and compatibility with the final merged Stage 13 contract.
