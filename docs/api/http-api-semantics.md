# WMS v1 HTTP Semantics & Operational Limits — Stage 13 WP7

**Document status:** Historical HTTP semantics audit. Use [WMS API v1 Reference](wms-api-v1-reference.md) for the current compatibility-oriented API entry point.
**Audit baseline:** `7db6742a6e04794eb5b31d54cc58aea45713d026` (historical)
**API prefix:** `/api/wms/v1`
**Evidence:** source inspection and targeted tests at the recorded baseline; revalidate any disputed behavior against current controllers and tests.

## 1. Route and method conventions

The Stage 13 inventory identifies 19 WMS controllers, all under the `/api/wms/v1` route family. The API is command-oriented for warehouse workflows: state transitions such as release, assign, start, confirm, dispatch, receive, approve, post and reverse use explicit POST command routes.

## 2. Existing status-code behavior

- Most WMS controller methods return their service result directly and do not declare `@ResponseStatus` or `ResponseEntity`. Spring MVC therefore uses HTTP 200 for successful responses, including many resource-creation POST operations.
- Explicit 204 responses are used by selected identity/authentication endpoints that intentionally return no body.
- No blanket conversion of creation endpoints from 200 to 201 is made in this historical change set. That would change the existing v1 contract and must be an explicit compatibility/versioning decision.
- Invalid request, authorization, conflict and internal-error responses use the WMS error contract established in WP3.

## 3. Missing-resource semantics

Before this WP, several WMS services used `IllegalArgumentException` both for invalid input and for a tenant-scoped lookup that returned no row. WP3 maps `IllegalArgumentException` to HTTP 400, so missing resources could be reported as invalid input.

This WP introduces `WmsResourceNotFoundException` and maps it to a stable, non-disclosing response:

- HTTP 404
- code `RESOURCE_NOT_FOUND`
- message `The requested resource was not found`

The service changes cover empty resource lookups across inbound receiving, outbound shipment, inter-warehouse transfer, replenishment planning/tasks, stock counts, inventory adjustments, inventory master data, inventory posting/reservation, warehouse-adjacent putaway, picking, packing, shipping and role/membership management. Caller-selected resource IDs now map to the typed 404 category. Dedicated generated-ID lookups retain internal state-failure semantics where the service explicitly distinguishes them. Validation failures, workflow-state conflicts and authorization failures retain their existing categories.

The MockMvc contract test verifies the status and stable error code and ensures the internal exception message is not exposed. Service tests verify role and membership lookup behavior.

## 4. Operational request limits

- The 19-controller route inventory contains no multipart upload endpoint.
- The inspected `application-wms.properties` does not explicitly configure servlet request size or multipart size limits.
- Collection bounds are handled by WP4; existing inventory balance/ledger queries already expose page/size controls.
- No arbitrary global request-body limit is introduced here because no evidence yet establishes a safe payload threshold for all WMS command DTOs. A deployment-level limit should be chosen and documented during deployment/production-readiness work, with targeted limits for any future bulk endpoint.

## 5. Idempotency and command semantics

Idempotency is not inferred merely from HTTP POST. Existing command-specific request identifiers and database uniqueness/reversal constraints remain authoritative. This WP does not add a generic idempotency filter or change command retry behavior.

## 6. Compatibility decisions

1. Preserve existing successful status codes and response bodies for WMS v1.
2. Return 404 only for confirmed missing tenant-scoped resources; do not turn permission denials into 404.
3. Preserve 400 for malformed/invalid input, 403 for authorization denial, 409 for state/version/integrity conflicts and 500 with a redacted message for unexpected failures.
4. Do not add generic request-size limits without a documented payload budget and representative tests.
5. Reassess response status conventions only as an explicit future API version/compatibility decision.

## 7. Remaining verification

| Item | Status | Follow-up |
|---|---|---|
| Existing success status defaults | **VERIFIED — source inspection** | WP9 confirms representative controller responses. |
| Missing-resource response contract | **PARTIALLY VERIFIED** | MockMvc verifies the generic WMS 404 contract; role/membership service tests verify selected lookups. Full Java 21/Testcontainers CI and WP9/WP10 route-family coverage remain required. |
| Multipart route inventory | **VERIFIED — source inspection** | Recheck when upload functionality is introduced. |
| Global request-size budget | **UNVERIFIED / not configured in WMS profile** | Decide with deployment limits and realistic payload sizes. |
| Every workflow's missing-resource path | **PARTIALLY VERIFIED** | Expand only where source audit identifies an empty lookup incorrectly mapped to 400. |

