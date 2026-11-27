# WMS v1 Request/Response Contract Audit — Stage 13 WP5

**Document status:** Historical API contract audit. Use [WMS API v1 Reference](wms-api-v1-reference.md) for the current compatibility-oriented API entry point.
**Audit baseline:** `7db6742a6e04794eb5b31d54cc58aea45713d026` (historical)
**API prefix:** `/api/wms/v1`
**Evidence type:** source inspection at the recorded baseline; this document is supporting audit evidence, not a substitute for current controller and test verification.

## 1. Objective

Record the existing request and response shapes of WMS v1 and identify only concrete contract risks. This audit does not authorize a blanket conversion of `Map<String, Object>` responses into DTOs or a breaking API redesign.

## 2. Request validation

The Stage 13 controller audit covered all 19 identified WMS HTTP controllers. Every inspected method parameter annotated with `@RequestBody` is also annotated with `@Valid`.

Nested request collections also use element-level validation in representative workflows, including outbound shipment allocations, stock transfer lines/allocations, and nested warehouse/location requests.

This is source-level evidence. It does not by itself prove that every constraint behaves correctly over HTTP; malformed JSON, invalid path/query values, nested validation and boundary values remain part of the WP9 HTTP test matrix.

## 3. Existing response-shape inventory

The API is not currently based on one universal response envelope.

| Area / representative controller | Collection response | Detail / command response | Source observation |
|---|---|---|---|
| Warehouse and location management | `List<Map<String,Object>>` | `Map<String,Object>` | Warehouse/location records are assembled by the application service. |
| Role and permission management | `List<Map<String,Object>>`, `List<String>` | `Map<String,Object>` or no response body for removal | Some endpoints intentionally return simple permission-code lists. |
| Outbound shipments | `List<Map<String,Object>>` | `Map<String,Object>` | Request DTOs are Java records. |
| Inter-warehouse transfers | `List<Map<String,Object>` | `Map<String,Object>` | Request DTOs are Java records. |
| Replenishment tasks | `List<Map<String,Object>` | `Map<String,Object>` | Request DTOs are Java records. |

Authentication and identity adapters also use dedicated request/response records. The broader source audit found a mixture of records, maps, lists of maps and simple lists across the 19 controllers.

**Decision:** preserve existing v1 response keys and shapes unless WP9/WP10 demonstrates a concrete ambiguity, data leak or client-compatibility defect. Do not introduce a global `data/meta/error` envelope as an incidental cleanup.

## 4. Request field conventions observed

- Public identifiers such as `warehousePublicId`, `itemPublicId`, `locationPublicId` and `membershipPublicId` are used at the HTTP boundary rather than exposing database primary keys.
- Quantity inputs in the inspected outbound, transfer and replenishment request records use `BigDecimal`, positive/non-negative validation as appropriate, and `@Digits(integer = 15, fraction = 4)`.
- Workflow commands expose optimistic version values as `Long` where the transition requires an expected version.
- Idempotent commands expose UUID request identifiers in request records.
- `scheduledAt` is represented as `LocalDateTime` in inspected outbound and transfer request records. A `LocalDateTime` has no UTC offset or zone; API reference documentation must state the intended interpretation explicitly. No automatic conversion to a different Java time type is made in this WP.

These are representative source observations, not a claim that every field across every controller has identical constraints.

## 5. Compatibility and implementation policy

1. Keep the `/api/wms/v1` prefix and existing field names.
2. Keep existing response collection and object shapes by default.
3. Do not serialize persistence entities directly.
4. Do not change numeric precision or silently coerce quantity values.
5. Keep optimistic version and request-ID semantics intact.
6. Clarify timestamp interpretation in API documentation before frontend integration.
7. Add typed response DTOs only when a specific endpoint has a demonstrated schema ambiguity, accidental field exposure, or a real compatibility requirement.
8. Any intentional breaking change requires an explicit versioning decision; do not silently change v1.

## 6. Open verification items

| Item | Status | Follow-up |
|---|---|---|
| Bean Validation across all inspected request bodies | **VERIFIED — source inspection** | WP9 verifies representative runtime failures over HTTP. |
| Full response schema and nullability for every route | **PARTIALLY VERIFIED** | Complete route-family contract tests in WP9 and compatibility review in WP10. |
| Jackson serialization of JDBC values, timestamps and decimals | **UNVERIFIED — runtime contract** | Assert representative JSON types and precision in WP9. |
| Time-zone interpretation of `LocalDateTime` request fields | **UNVERIFIED — contract decision** | Document the agreed business interpretation before frontend integration. |
| Backward compatibility of all existing response keys | **UNVERIFIED — client impact** | WP10 verifies the integrated v1 contract; no frontend assumptions are invented here. |

## 7. Exit criteria

WP5 is complete when the request/response inventory is reconciled against the final WP4 pagination contract, all concrete serialization or compatibility risks have targeted fixes/tests, and no broad response-shape refactor has been introduced without evidence.
