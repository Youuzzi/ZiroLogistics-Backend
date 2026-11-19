# WMS v1 Compatibility & API Integration Audit — Stage 13 WP10

**Baseline:** `564a5e25b131101a2948b45784fd1efa7883176d` (WP8 API reference merged; WP7 runtime behavior included)
**Evidence:** source/configuration inspection. Runtime compatibility is subject to WP9 controller tests and final Stage 13 CI.

## 1. API version and route isolation

- All 19 identified WMS controllers use the `/api/wms/v1` route family.
- No second WMS API version was found in the controller inventory.
- The WMS route prefix is separate from the legacy controller surface; no duplicate WMS v1 route family was identified.
- WMS HTTP controllers are profile- and servlet-conditional and require `wms.security.jwt.enabled=true` for registration.

## 2. Runtime/package isolation

`WmsRuntimeApplication` scans, entity-scans and enables repositories only under `com.zirocraft.zirologistics.wms`. This is the dedicated WMS runtime boundary and avoids automatically pulling legacy controllers, entities and repositories into that runtime.

The root `ZirologisticsApplication` remains a separate application entry point. If it is run with the WMS profile, both legacy root-level components and WMS components can be present in the same Spring context; the WMS exception advice is scoped to the WMS package and has highest precedence. WP3's MockMvc contract test explicitly registers both the legacy `GlobalExceptionHandler` and WMS advice and verifies the WMS error contract wins for WMS exceptions.

**Decision:** retain the dedicated WMS runtime as the preferred WMS deployment entry point. Do not use package scanning changes as a substitute for route authorization.

## 3. Authentication and authorization compatibility

- JWT-disabled WMS HTTP security denies every request.
- JWT-enabled WMS HTTP security authenticates every route except the exact anonymous POST allowlist when `wms.security.http.auth-endpoints-enabled=true`.
- Turning the anonymous endpoint switch off does not unregister protected operational routes.
- Invitation issuance and existing-account acceptance remain protected; new-user acceptance is in the exact public allowlist.
- Effective tenant membership and permissions are resolved from persisted database state, not JWT permission claims or frontend state.

**Documentation gap:** `docs/architecture/WMS_AUTHENTICATION_HTTP_API_CONTRACT.md` still contains stale deployment wording that says both JWT and anonymous-auth endpoint flags are required for all identity HTTP endpoints. WP2 changed registration semantics: JWT enables protected WMS HTTP controllers, while the second flag controls only the exact anonymous POST allowlist. WP8's curated API reference documents the corrected behavior, but does not itself revise the older authentication contract. WP11 must reconcile that file during the final documentation/configuration pass.

## 4. Error-handler compatibility

The legacy `GlobalExceptionHandler` returns its existing legacy response structure. The WMS handler returns a separate machine-readable WMS error contract, is scoped to `com.zirocraft.zirologistics.wms`, and has `Ordered.HIGHEST_PRECEDENCE`.

The WMS advice explicitly handles validation, authentication, authorization, typed not-found and conflict cases; unexpected exceptions return a generic 500 message. The WP3 test proves precedence when both advice instances are registered. WP7 extends the typed 404 path to additional tenant-scoped workflow lookups.

## 5. Request/response and frontend compatibility

- WMS v1 retains endpoint-specific record, map, list and no-content response shapes; no global envelope is introduced.
- Successful create/command routes commonly return 200; selected identity commands return 204. These are preserved for v1.
- Request DTOs use Bean Validation; WP5 records source-level coverage and WP9 adds representative HTTP validation tests.
- Collection limits are preserved; WP4 adds page/size to the remaining unbounded transfer and role reads without changing list response shapes.
- Scheduled `LocalDateTime` fields remain a contract ambiguity because they do not carry a timezone/offset. Frontend integration must not assume UTC until the business interpretation is decided.

## 6. OpenAPI / API description

The inspected Maven dependency list contains no springdoc/OpenAPI dependency and the repository has no generated OpenAPI document at this baseline. WP8 therefore adds a curated Markdown API reference linked to the historical route inventory and the detailed request/response, security and HTTP semantics contracts. This is intentionally not presented as a machine-generated OpenAPI schema.

If a generated OpenAPI document is required for external consumers, add it as a separately reviewed compatibility/security change and verify that protected schemas/routes are not unintentionally exposed.

## 7. Deployment configuration mismatch — deferred to WP11 / Stage 17

The existing `compose.yml` and default `application.properties` describe the legacy application configuration:

- Compose sets `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET_KEY`, and `FLYWAY_ENABLED`.
- Compose starts the default application image without selecting the dedicated WMS runtime/profile.
- The WMS profile uses `WMS_DB_URL`, `WMS_DB_USERNAME` and `WMS_DB_PASSWORD`; Flyway is controlled separately by `WMS_FLYWAY_ENABLED` and defaults to disabled.
- WMS JWT key material is required only when `WMS_JWT_ENABLED=true`. The anonymous authentication endpoints additionally require the explicit endpoint switch and the configured MySQL-backed throttle storage/policy; SMTP and email-outbox encryption secrets are required only when their respective opt-in features are enabled.
- Compose's default MySQL database name is `zirologistics`, not the planned isolated `zirologistics_wms` runtime database.

This means the current Compose configuration must not be described as a verified WMS deployment path. The existing default development credentials also must not be treated as production secrets.

**Ownership split:** WP11 owns the repository-wide documentation and configuration audit, including README, Dockerfile, Compose, deployment YAML, application properties, Maven/CI configuration and `.env.example`. WP11 will correct stale or misleading developer setup instructions and align the files with the dedicated WMS runtime without claiming production deployment readiness. Stage 17 remains responsible for final environment-specific deployment architecture, production secrets, hosting/network policy, rollout and operational validation. Never point WMS Flyway at the legacy database or the frozen reference database.

## 8. Final compatibility conclusions

| Area | Classification | Result |
|---|---|---|
| WMS route prefix/version | **VERIFIED — source inspection** | Existing v1 retained; no second WMS version identified. |
| Dedicated WMS package scan | **VERIFIED — source inspection** | Dedicated runtime isolates WMS components. |
| WMS error advice precedence | **VERIFIED — targeted MockMvc test** | WMS contract wins over legacy advice in the combined context. |
| Exact public route allowlist | **VERIFIED — security tests** | Only exact anonymous POST routes are permitted when enabled. |
| Full client compatibility for every response | **PARTIALLY VERIFIED** | WP9/WP11 integration matrix remains required. |
| Generated OpenAPI schema | **UNAVAILABLE at baseline** | Curated API reference documented; generated schema is a separate decision. |
| Current Docker Compose as WMS deployment | **UNVERIFIED / NOT SUPPORTED** | Configuration targets the legacy runtime and database variables. WP11 / Stage 17 own remediation. |

## 9. Exit criteria

WP10 is complete when WP9 tests pass on the latest main baseline, all Stage 13 route/security/error/pagination decisions are reconciled, no known v1 compatibility break is introduced, and the deployment mismatch is explicitly carried into WP11 / Stage 17 rather than hidden or prematurely patched.
