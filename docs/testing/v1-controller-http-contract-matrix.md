# WMS v1 Controller HTTP Contract Test Matrix

> **Historical test inventory:** This matrix records the HTTP test baseline at the commit shown below. Test names and historical coverage do not by themselves prove current route behavior or assertion completeness; use current CI and the API reference for present status.


**Baseline:** `e3bbfc48cd987b549f1d240163cbde33892a28c5` (WP4 merged; WP7 pending)
**Test style:** Spring MVC slice + Spring Security test authentication, with application services mocked.
**Purpose:** Verify the transport boundary independently of the existing service/Testcontainers workflow suites.

## 1. Existing HTTP coverage at the recorded baseline

The baseline contained six HTTP/security test classes:

| Test class | Existing focus |
|---|---|
| `WmsApiErrorContractTest` | Error mapping, redaction, malformed JSON and conflict categories |
| `WmsAuthenticationControllerHttpTest` | Credential login, tenant selection, password-reset generic response |
| `WmsPasswordChangeControllerHttpTest` | Protected password-change route remains registered and rejects anonymous requests when public auth endpoints are disabled |
| `WmsHttpSecurityAuthEndpointsDisabledTest` | Protected routes stay registered when anonymous auth endpoints are disabled |
| `WmsHttpSecurityDisabledTest` | JWT-disabled mode denies all requests |
| `WmsHttpSecurityEnabledTest` | Exact anonymous allowlist and bearer authentication |
| `WmsTenantProfileControllerHttpTest` | Current-tenant profile read/update and DTO validation |

Coverage was concentrated in authentication/security and tenant profile. Workflow modules were primarily covered below the controller layer.

## 2. Added representative route-family HTTP tests

| Controller | Contract cases |
|---|---|
| Warehouse | Unauthenticated access rejected; protected route remains registered with anonymous auth disabled; authenticated collection response; invalid country-code validation; create request maps only validated fields and authenticated principal. |
| Role management | Unauthenticated access rejected; default page/size forwarding; explicit page/size forwarding; invalid page maps to stable 400; invalid create DTO rejected before service invocation. |
| Inter-warehouse transfer | Unauthenticated access rejected; explicit page/size forwarding; invalid size maps to stable 400; empty line list rejected; nested allocation quantity validation enforced before service invocation. |

These tests mock application services deliberately: they prove HTTP routing, authentication, validation, serialization and error translation, not SQL tenant isolation or workflow state correctness. Those remain covered by application-service and Testcontainers suites plus WP6/WP10 negative cases.

## 3. Required invariants

- Every operational route requires a WMS authenticated principal.
- Public auth endpoint toggle controls only the exact anonymous allowlist; disabling it does not remove protected operational controllers or the authenticated password-change route.
- Request DTO validation occurs before application-service invocation.
- Nested list elements are validated through `@Valid`.
- Pagination query parameters are forwarded without coercion; application services enforce the hard bounds.
- WMS exceptions produce stable error status/code/message and do not leak internal details.
- Successful v1 response shapes and status codes remain unchanged.

## 4. Remaining matrix work

| Area | Status | Next coverage |
|---|---|---|
| Authentication/security | **EXISTING** | Keep exact anonymous allowlist and JWT-disabled tests. |
| Tenant profile | **EXISTING** | Preserve optimistic version and field allowlist tests. |
| Warehouse | **ADDED** | Add detail/location route coverage only if later API audit identifies a concrete risk. |
| Roles/pagination | **ADDED** | Retain service tests for query bounds and tenant scoping. |
| Transfers/pagination | **ADDED** | Retain service/Testcontainers tests for workflow and posting semantics. |
| Inventory balances/ledger | **NOT YET COVERED BY CONTROLLER SLICE** | Add targeted HTTP pagination and invalid query tests. |
| Inbound/outbound/stock count/adjustment/replenishment | **NOT YET COVERED BY CONTROLLER SLICE** | Select representative DTO/command boundaries in the final matrix; avoid duplicating existing service tests. |
| Cross-tenant negative HTTP probes | **NOT YET PROVEN** | WP10 must prove Tenant A cannot access or mutate Tenant B resources through representative route families. |

## 5. Test strategy decision

Do not create one enormous all-controller Spring context or duplicate every service test through MockMvc. Keep HTTP tests focused on transport concerns and use existing MySQL 8.4 Testcontainers suites for transactional, tenant and concurrency invariants. Expand the matrix only where a route family has a distinct request contract or security boundary.
