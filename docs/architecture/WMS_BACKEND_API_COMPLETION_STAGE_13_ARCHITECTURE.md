# WMS Backend API Completion — Stage 13 Architecture & Delivery Contract

**Status:** Proposed delivery contract; WP1 baseline audit in progress.  
**Starting baseline:** `0441d900f19c028bc5f413695205d04630e73226`.  
**Runtime:** Java 21, Spring Boot 3.5.13, WMS-only servlet runtime, MySQL 8.4.  
**API prefix:** `/api/wms/v1`.

## 1. Objective

Complete the existing WMS HTTP API as a coherent, secure, versioned, documented and supportable contract for the backend delivered through Stages 1–12. This is API completion and hardening, not a new business-capability stage.

The API must expose existing WMS workflows without bypassing application services, tenant membership resolution, persisted permissions, inventory posting boundaries, workflow state guards, transaction guarantees or audit behavior.

## 2. Scope and non-goals

In scope: route inventory; controller registration and security; validation and public errors; bounded collection reads; DTO/status consistency; tenant, membership and permission checks; idempotency and optimistic concurrency at HTTP boundaries; API reference; controller-level HTTP tests.

Out of scope: new warehouse workflows, duplicate endpoints, frontend implementation, business logic in controllers, weakened security or audit, automatic retry of non-idempotent commands, breaking changes without versioning, and schema changes unless proven essential. Any schema addition must be additive V27+; V1–V26 remain immutable.

## 3. Baseline inventory — source inspection

### 3.1 Existing route families

The WMS runtime has HTTP adapters for authentication/invitations; tenant profile and role/permission management; warehouse/location and item/category/identifier/UOM/lot master data; inventory posting/balances/ledger; inbound receiving/putaway; outbound shipment/picking/packing/shipping; inter-warehouse transfer; stock count/adjustment; replenishment; and operational reporting.

All identified controllers are under the WMS package scan, use `/api/wms/v1`, and are guarded by the WMS profile, servlet runtime and `WmsIdentityHttpEnabledCondition`.

### 3.2 Findings and evidence boundaries

| Finding | Current evidence | Initial classification |
|---|---|---|
| Operational and identity controllers share `WmsIdentityHttpEnabledCondition`, which requires both `wms.security.jwt.enabled=true` and `wms.security.http.auth-endpoints-enabled=true`. The security chain separately uses the second switch only to decide whether public auth POST routes are permitted. | Direct source inspection of controllers, condition and security filter chain. No production-profile test proves intended behavior with JWT enabled and auth endpoints disabled. | **PARTIALLY VERIFIED — potential route-registration/configuration defect.** WP2/WP6 must prove intent and add a matrix test. |
| `WmsAuthenticationExceptionHandler` covers authentication, identity, tenancy, authorization, warehouse and inventory packages, but not later workflow packages (inbound, adjustment, outbound, picking, packing, putaway, replenishment, reporting, shipping, stock count, transfer). A global legacy `GlobalExceptionHandler` also exists. | Direct source inspection; no complete runtime exception matrix. | **VERIFIED source gap; runtime response inconsistency UNVERIFIED.** WP3 must test representative exceptions across route families. |
| Inventory balances and ledger expose page/size with service-enforced bounds (page non-negative, size 1–200). Most other collection controllers expose no page/size parameters and return lists. | Controller inspection and inventory query service inspection. Service-side bounds for other lists remain to be audited. | **PARTIALLY VERIFIED — bounded collection contract gap.** WP4 audits query implementations before changing APIs. |
| Transport shapes are mixed: records for authentication, `Map<String,Object>` / `List<Map<String,Object>>` for many operational endpoints, and raw lists for some collections. | Direct controller source inspection. | **VERIFIED source observation; compatibility impact not measured.** WP5 changes only where ambiguity or risk is concrete. |
| No OpenAPI/springdoc dependency or generated API specification is present in the repository tree/POM at this baseline. | Direct POM and repository tree inspection. | **VERIFIED source observation.** WP8 evaluates the smallest sustainable documentation mechanism; no dependency is added automatically. |
| Controller HTTP tests are concentrated in authentication and tenant profile/security slices; most workflow evidence is in application/service integration tests. | Test tree inspection. | **PARTIALLY VERIFIED.** WP9 builds a route-family coverage matrix and fills meaningful gaps. |
| Existing API version prefix is `/api/wms/v1`; no second WMS API version is identified. | Route inventory. | **VERIFIED.** Preserve v1 compatibility unless an explicit versioned breaking-change decision is approved. |

Source observations are not runtime proof and do not justify broad refactoring by themselves.

## 4. API invariants

1. Tenant identity comes from the authenticated WMS principal and active membership, never a client-selected tenant ID.
2. Every tenant-scoped operation re-resolves current membership and persisted permissions at the application boundary.
3. Controllers remain thin transport adapters; application services own business policy and transactions.
4. Inventory balances, ledger and serial state are changed only through the established inventory posting boundary.
5. Workflow state transitions, optimistic versions, request identity/idempotency and four-eyes controls remain server-enforced.
6. Collection reads are bounded, tenant-scoped, deterministic and explicit about pagination/filter semantics.
7. Public errors are stable and machine-readable, without SQL, stack trace, token, credential, tenant-existence or internal-policy leakage.
8. Public auth routes remain an exact allowlist. Disabling public auth endpoints must not accidentally disable unrelated authenticated business routes unless explicitly documented as a deployment-wide switch.
9. Successful command replay semantics and request-ID behavior must not change as a side effect of HTTP normalization.
10. Legacy routes and security behavior remain unchanged unless tests prove an intentional migration is required.

## 5. Work packages

### WP1 — API Architecture & Baseline Audit
Inventory routes, DTOs, service delegates, permissions, response types, status codes and tests; record deployment switches and v1 policy; prioritize evidenced gaps; deliver this contract and endpoint inventory.

### WP2 — Route Registration, Versioning & Security Matrix
Verify WMS route registration; test JWT disabled, JWT enabled/auth endpoints disabled, and JWT enabled/auth endpoints enabled; enforce exact public-route allowlist; ensure protected business routes remain available when authenticated API is enabled; test unknown routes/methods and cross-tenant identifiers.

### WP3 — Unified WMS Error & Validation Contract
Audit advice coverage/precedence; standardize safe error codes/messages and status semantics; cover malformed JSON, Bean Validation, path/query validation, not-found, conflict, forbidden, unauthorized and unexpected errors; preserve legacy non-WMS behavior; prevent WMS errors falling through to legacy response format.

### WP4 — Bounded Collection Reads
Audit all list/search query implementations; add consistent pagination and deterministic ordering where reads can be unbounded; standardize metadata and safe maximum page sizes; add allowlisted filters/sorting only when needed; avoid unnecessary pagination on small bounded catalogues.

### WP5 — Request/Response Contract Consistency
Review DTO fields, nullability, numeric precision, timestamps/time zones, enum validation and optimistic versions; replace generic map responses only where they create concrete ambiguity/risk; keep additions backward compatible; never expose persistence entities/internal IDs.

### WP6 — Tenant, Permission & Command Boundary Verification
Verify active membership, persisted permissions, tenant-scoped path IDs/nested ownership, idempotency, optimistic concurrency, state transitions and audit actor context; correct demonstrated defects and add cross-tenant/permission-denial HTTP tests.

### WP7 — HTTP Semantics & Operational Limits
Review method/status semantics (200/201/202/204/400/401/403/404/409/413/415/422/429/500 as applicable), request-body and pagination limits, timeout-sensitive reads, authentication throttling and sensitive logging. No blanket rate limits or automatic retries without evidence/policy.

### WP8 — API Reference & Versioning Documentation
Publish endpoint reference with auth, permissions, request/response examples, errors, pagination and idempotency; evaluate OpenAPI versus maintained contract docs; document v1 compatibility/deprecation policy; keep reference synchronized with controllers/DTOs.

### WP9 — Controller HTTP Contract Test Matrix
Add focused MockMvc tests for each route family and representative success/failure paths; verify validation, auth, permission denial, tenant scope, error shape, status and response schema; reuse MySQL integration tests for business invariants; preserve legacy tests.

### WP10 — Compatibility & API Integration Audit
Verify route uniqueness, response compatibility, frontend-consumable contracts, pagination stability, deterministic ordering, timestamp serialization, enum evolution and migration notes; confirm no unimplemented/disabled functionality is accidentally exposed.

### WP11 — Final API Audit & CI
Re-run endpoint inventory; verify delivered PR CI and sequential merges; run full Java 21/MySQL 8.4 Testcontainers regression on exact final main SHA; record residual evidence limits honestly.

## 6. Delivery and CI rules

- Focused feature branches and PRs; never push directly to `main`.
- Sequential merges. While a PR CI runs, audit the next independent WP; do not implement/merge dependent changes against an unmerged base.
- Docs-only PRs use existing docs-only CI. Code, SQL, config, dependency, workflow or test changes require full regression.
- Never weaken tests, skip tests, bypass Flyway or use user-owned databases.
- Test migrations only in disposable MySQL 8.4 Testcontainers. V1–V26 immutable; future additions V27+ only.
- Keep feature branches until backend completion and final cleanup audit.
- Stage 13 completes only after WP1–WP11 integrate and final Main CI is green.

## 7. Evidence labels

- **VERIFIED:** direct runtime/test/CI evidence on exact commit.
- **PARTIALLY VERIFIED:** evidence exists but not all relevant cases.
- **INFERRED:** source structure only, no runtime proof.
- **UNVERIFIED:** no sufficient evidence yet.
- **PROPOSED:** planned work/design not implemented.

## 8. Exit criteria

Deliver a complete, stable and documented WMS v1 HTTP contract with verified route registration, security, tenant/permission boundaries, validation, consistent errors, bounded collection reads, compatibility rules and route-level test coverage. This does not claim production readiness; structural audit/refactoring, security QA, deployment and production readiness remain later stages.
