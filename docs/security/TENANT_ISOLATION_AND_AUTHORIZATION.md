# Tenant Isolation and Authorization

**Status: AUDIT BASELINE — PARTIALLY VERIFIED; NOT A TENANT-ISOLATION CERTIFICATION**
**Reviewed branch:** `main`
**Reviewed commit:** `3f130a72e4f9b5b77e252158e94fff47d37280c7`
**Scope:** WMS tenant identity resolution, HTTP authorization boundary, tenant-scoped operational services, persistence access patterns, and available isolation tests.

## 1. Purpose and assurance statement

This document records the current source-level evidence for tenant isolation and authorization. It distinguishes implemented controls, tested behavior, and remaining verification work.

The current implementation has meaningful tenant-boundary controls: authenticated user identity is bound to an explicitly selected active membership; effective permissions are resolved from persisted role assignments; tenant-scoped application services receive the authenticated principal; and inspected operational queries commonly constrain resource access by the resolved tenant ID.

However, the evidence reviewed here does **not** prove that every route, query, nested resource, asynchronous process, or administrative execution path is isolated. The assurance status therefore remains **PARTIALLY VERIFIED**. Do not describe the application as fully tenant-isolation certified or production-ready on the basis of this audit.

## 2. Security invariants

The following invariants are the required authorization contract:

1. A global user identity alone is not a tenant authorization context.
2. A selected membership public ID is a selector, not a credential. It must be resolved together with the already authenticated user public ID.
3. Only an active user, active membership, active tenant, and active tenant role may contribute effective access.
4. Tenant identity used by services and SQL must come from the database-resolved authenticated principal, not from an arbitrary request parameter, request body, header, or untrusted token field.
5. Every tenant-owned resource read or mutation must be scoped to the resolved tenant. Nested resources must also be checked against their expected parent and tenant.
6. Every protected operation must check its specific permission. Missing permission, missing context, invalid state, or unresolved ownership must fail closed.
7. Cross-tenant resource identifiers should not reveal resource existence; tenant-scoped lookup should return the same not-found behavior as an unknown identifier where appropriate.
8. Platform administration must use a distinct control-plane identity and authorization boundary. A tenant role must never authorize platform operations, and a platform identity must not silently bypass tenant authorization.

## 3. Implemented identity and membership resolution

### 3.1 Persisted membership lookup

Source:
- `src/main/java/com/zirocraft/zirologistics/wms/authorization/infrastructure/persistence/WmsMembershipAccessRepository.java`
- `src/main/java/com/zirocraft/zirologistics/wms/authorization/application/WmsMembershipAccessService.java`

The native membership-access query binds both `users.public_id` and `tenant_memberships.public_id`. It requires the user, membership, and tenant to be ACTIVE. Membership-role assignment is constrained by the membership tenant; the role must belong to the same tenant and be ACTIVE. Effective permission codes are then collected from the role-permission assignment and permission catalogue.

The service rejects blank identifiers and denies when no matching active membership row is found. The returned `WmsMembershipAccess` is immutable and carries internal user, tenant, and membership identifiers plus the resolved permission set.

This is positive evidence for the membership-resolution boundary. It does not by itself prove that every downstream service uses the resolved tenant correctly.

### 3.2 HTTP authentication boundary

Source:
- `src/main/java/com/zirocraft/zirologistics/wms/security/config/WmsSecurityConfiguration.java`
- `src/main/java/com/zirocraft/zirologistics/wms/security/application/WmsJwtTokenService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/security/application/WmsJwtAuthenticationConverter.java`

The WMS servlet security configuration installs a catch-all security chain for the WMS runtime. With JWT enabled, requests are authenticated by bearer token by default and only explicitly configured authentication/action-token endpoints are public. With JWT disabled, the chain denies every request. The WMS runtime is stateless and disables form login and HTTP Basic.

The token pipeline validates the token and resolves the selected membership against current database state before constructing the WMS principal. Effective permissions are database-derived rather than trusted from caller-supplied permission lists.

This describes the WMS security chain; it is not evidence of a separate, implemented platform control-plane authentication chain. Platform administration architecture is documented separately and remains an implementation gap.

## 4. Tenant-scoped application and persistence pattern

The route-boundary inventory in the repository identifies 19 WMS controller classes covering authentication, invitations, tenant profile, roles, warehouse/location, inventory/master data, inbound, putaway, outbound, picking, packing, shipping, transfer, stock count, adjustment, replenishment, and reporting.

The inspected tenant-scoped application services generally follow this pattern:

1. Resolve current membership through `WmsMembershipAccessService.resolve(userPublicId, membershipPublicId)`.
2. Compare resolved user, tenant, and membership identity with the authenticated principal.
3. Check an operation-specific permission.
4. Use the resolved internal tenant ID in SQL predicates and joins for tenant-owned records.
5. Resolve child records in the context of the expected tenant and parent resource.

Most operational modules use explicit SQL through `JdbcTemplate`; the WMS source tree also contains a small number of Spring Data repository interfaces for identity and authorization persistence. The presence of tenant predicates in representative service methods is useful evidence, but it is not a substitute for auditing every SQL statement and every call path.

### Representative verified test patterns

- Warehouse MySQL integration test creates two tenants, verifies tenant A sees only its own warehouse, rejects tenant B's warehouse identifier, and exercises a foreign parent-location reference.
- Warehouse foreign-tenant service test verifies the SQL lookup includes both tenant ID and public ID.
- Picking and packing boundary tests verify foreign task/session identifiers are looked up under the authenticated tenant.
- Replenishment boundary test verifies a foreign replenishment task is not resolved under the authenticated tenant.
- Transfer boundary test verifies a foreign transfer ID is not resolved under the authenticated tenant.
- Membership and permission integration tests verify that another user's membership public ID cannot be used to resolve access, tenant A permissions do not leak into tenant B, inactive roles contribute no permissions, and suspended user/membership/tenant states are denied.

## 5. Existing isolation evidence and its limits

The repository contains MySQL/Testcontainers and focused service/HTTP tests across multiple areas, including identity and membership resolution, warehouse, inbound, outbound, inventory posting, putaway, stock count, replenishment, reporting, selected transfer/adjustment/shipping boundaries, and role management.

Evidence types must not be conflated:

- **MySQL/Testcontainers integration tests** exercise actual MySQL schema and query behavior for the covered scenarios.
- **Mock-JDBC/service boundary tests** verify selected SQL predicates and service behavior but do not execute the SQL against MySQL.
- **HTTP boundary tests** verify selected authentication, route, validation, and principal-forwarding behavior; they do not automatically prove every endpoint's cross-tenant behavior.
- **Source review** identifies implementation patterns but cannot prove runtime behavior for every branch or dynamic query.

The existing QA closure evidence document explicitly records the overall tenant-isolation assessment as partially verified and the claim that no cross-tenant leak exists anywhere as unverified. This audit preserves that conservative status.

## 6. Required follow-up verification

The following work is required before tenant isolation can be considered closed:

### 6.1 Route-to-query traceability

- Enumerate every controller mapping, including HTTP method, route, request DTO identifiers, and service entry point.
- Trace each operation through service methods to all SQL statements and repository calls.
- Identify all tenant-owned tables touched by each query and verify tenant constraints on both primary and joined/nested resources.
- Review list, search, count, export, detail, update, delete, and state-transition paths separately.
- Verify that bulk operations cannot mix identifiers from different tenants.

### 6.2 Nested-resource and indirect-reference tests

Add or strengthen MySQL integration cases for foreign-tenant identifiers supplied as:
- parent IDs and child IDs in the same request;
- location, warehouse, item, lot, serial, inventory, order, task, shipment, transfer, and adjustment references;
- request-body references used by create/update/confirm/post operations;
- collection filters, pagination, search terms, and bulk action payloads.

Tests must assert no cross-tenant read, mutation, side effect, or information disclosure.

### 6.3 Non-HTTP execution paths

Inventory scheduled jobs, asynchronous listeners, message consumers, command runners, import/export jobs, and operational maintenance processes. For each path, define the trusted tenant source, actor identity, permission/policy enforcement, and audit attribution. A string operator reference is not proof of an authenticated platform operator.

### 6.4 Platform control-plane separation

The platform administration design requires a distinct operator identity, token audience, authorization catalogue, and HTTP security chain. Until implemented and tested, lifecycle command execution must remain an explicitly controlled operational path and must not be represented as authenticated platform-user access.

### 6.5 Regression and evidence gates

- Add negative cross-tenant tests at the service and HTTP boundaries for every route family, prioritizing write/post/approve/confirm operations.
- Run MySQL 8.4 Testcontainers integration tests for database ownership predicates and nested references.
- Keep authorization and tenant isolation tests in required CI validation.
- Capture exact commit SHA and CI run IDs for every closure claim.
- Re-run the full suite on the integrated `main` commit after ordered PR merges.

## 7. Current findings register

| ID | Finding | Assessment | Required action |
|---|---|---|---|
| TI-01 | Membership resolution binds authenticated user and selected membership | Implemented; focused integration evidence exists | Preserve as invariant |
| TI-02 | Active user, membership, tenant, and active role gate effective permissions | Implemented in resolver query | Preserve and retain regression tests |
| TI-03 | Tenant-scoped services use resolved tenant context and operation permissions | Verified in inspected services; not exhaustive | Complete route-to-query trace |
| TI-04 | Cross-tenant resource substitution is rejected in several module tests | Verified for tested scenarios | Expand to all route families and write paths |
| TI-05 | All tenant-owned SQL and nested references are tenant-safe | Not yet verified exhaustively | Audit every SQL statement and indirect reference |
| TI-06 | All HTTP routes have equivalent negative cross-tenant coverage | Partially verified | Add route-family HTTP integration matrix |
| TI-07 | Background and command execution paths have a uniform tenant/actor policy | Not yet verified exhaustively | Inventory and test non-HTTP paths |
| TI-08 | Distinct authenticated platform operator boundary is implemented | Architecture documented; runtime implementation not established | Implement after tenant policy and permission catalogue are reconciled |

## 8. Explicit non-goals and safety constraints

This audit does not create a tenant, invoke first-tenant bootstrap, modify database contents, change Flyway migrations, alter Docker configuration, or access developer databases. The legacy `zirologistics_db` and frozen reference database `zirowms_v1` remain out of scope and must remain untouched.

No permission is granted by this document. Authorization changes require a reviewed permission catalogue, role matrix, enforcement-point implementation, and regression tests.

## 9. Closure criteria

Tenant isolation may be marked **VERIFIED FOR THE REVIEWED SCOPE** only when:
1. route-to-query traceability covers all exposed WMS operations;
2. tenant predicates and nested ownership checks are verified for all tenant-owned data access paths;
3. negative cross-tenant tests cover reads, lists/searches, writes, state transitions, bulk operations, and indirect references;
4. non-HTTP execution paths have explicit tenant and actor controls;
5. platform and tenant security boundaries are separately enforced;
6. CI passes on the integrated main commit and the evidence is recorded.

A passing test suite is evidence for the tested scope, not a universal proof beyond that scope.
