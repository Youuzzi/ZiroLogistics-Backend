# Platform Administration Implementation Plan

**Status:** Partially implemented plan. Platform identity/role persistence, JWT trust-domain primitives, persisted permission evaluation, operator-role governance, tenant lifecycle service authorization, and support-grant governance exist. End-to-end platform MFA/login HTTP, first-operator provisioning, administration controllers, and support-grant scope consumption remain open.
**Baseline commit:** `2b72fb4b7870e8312b643a2b5fb71321eab0ab45`.
**Primary architecture:** `docs/architecture/platform-administration-and-operations.md`.

## 1. Current implementation boundary

V29 establishes platform operators, roles, permissions, assignments, MFA/recovery persistence, and authentication events. V30 establishes support-grant governance. Service-layer primitives include platform credential authentication, platform JWT issuance/conversion, persisted permission evaluation, role assignment, tenant lifecycle authorization, and support-grant administration. These slices do not yet form a complete platform administration API or a first-operator onboarding workflow.


The repository has tenant-scoped authentication and authorization plus partial platform control-plane foundations. The platform bootstrap still creates a tenant owner, not a platform operator; no controlled first Platform Administrator provisioning operation is established.

Tenant lifecycle/provisioning code can record `actor_type = PLATFORM_OPERATOR` and an `operator_reference` string. That string is audit attribution only: the current lifecycle service validates its syntax but does not resolve it to an authenticated, persisted operator identity. It must not be exposed as an authenticated administrative API.

## 2. Control-plane/data-plane invariants

1. Platform and tenant identities are distinct principals, even if one human is separately enrolled in both systems.
2. Platform and tenant JWT audiences, token purposes, authentication chains, and route namespaces are distinct.
3. Platform permissions never enter the tenant permission catalogue; tenant roles never grant platform authority.
4. Tenant lifecycle operations require an authenticated platform principal and operation-specific permission.
5. Platform tenant metadata access does not grant access to warehouse transactions.
6. Support access is explicit, time-limited, reason-bound, revocable, and audited; no standing impersonation or platform god-mode.
7. Every command/background execution records a verifiable service/operator identity, not a caller-supplied label.

## 3. Implementation slices

### Slice 1 — Platform identity persistence
**Implemented foundation — V29.**
V29 adds platform operators, platform roles, platform permissions, operator-role assignments, role-permission grants, MFA/recovery persistence, and platform authentication events. A controlled first-operator provisioning operation remains open. Use opaque public IDs, normalized unique email, adaptive password hashes, lifecycle status, credential version, and UTC audit timestamps.

### Slice 2 — Platform authentication
**Partially implemented.** `PlatformCredentialAuthenticationService`, `PlatformJwtTokenService`, `PlatformJwtAuthenticationConverter`, and a separate platform JWT/security configuration exist. The credential use case is not composed into an HTTP adapter and has no confirmed concrete throttle implementation. The token issuer requires a recent successful MFA timestamp, but TOTP enrollment/verification, recovery, challenge orchestration, and login controllers remain open. Keep existing tenant token claims and tenant auth flow unchanged.

### Slice 3 — Platform authorization boundary
**Partially implemented.** A dedicated `/api/platform/v1/**` security chain, separate decoder, token-purpose validation, and cross-trust-domain rejection primitives exist. `PlatformPermissionEvaluator` reads current persisted grants and fails closed. No platform HTTP controllers were found, so endpoint-level authorization and HTTP integration coverage remain open.

### Slice 4 — Tenant lifecycle integration
**Service layer implemented; HTTP boundary open.** `PlatformTenantLifecycleApplicationService` derives the actor from Spring Security and applies action-specific permissions before invoking tenant lifecycle transitions. The separate command-mode lifecycle runner still uses a validated operator reference as audit attribution, not authenticated identity. No platform lifecycle controller is present.

### Slice 5 — Support access and break-glass
**Governance implemented; scoped access consumer open.** V30 and `PlatformSupportGrantService` implement request, approval/rejection, revocation, audit, a four-hour maximum, and a fixed read-only scope allowlist. No tenant data-service consumer is established that turns an approved grant into scoped access; no warehouse transaction or impersonation access is granted by the schema/service alone.

### Slice 6 — Platform role and permission governance
**Persistence and role-administration foundation implemented.** V29 seeds the six platform role definitions and permission catalogue; `PlatformOperatorRoleAdministrationService` enforces actor permission, delegation ceilings, no self-assignment, no routine `PLATFORM_OWNER` assignment, expiry, and audit. A complete platform HTTP administration surface and final role-to-permission product policy remain open. Billing role presence is not evidence that billing capabilities are implemented.

## 4. Seeded platform role catalogue

V29 seeds the following role codes, separate from tenant system-role templates. Their presence does not mean an initial operator has been provisioned or assigned them:
- `PLATFORM_OWNER`
- `PLATFORM_ADMIN`
- `PLATFORM_SUPPORT`
- `PLATFORM_BILLING_ADMIN`
- `PLATFORM_SECURITY_ADMIN`
- `PLATFORM_AUDITOR`

These are platform control-plane roles, not warehouse job titles. The permission catalogue carries risk levels, but the complete operational role-to-permission matrix and externally reachable administration workflow remain subject to implementation and verification. Billing permissions remain dormant until subscription and invoice workflows are implemented.

## 5. Verification gates

- Migration clean-install and upgrade tests on MySQL 8.4.
- Platform operator lifecycle and credential-version invalidation tests.
- MFA-required authorization tests for privileged permissions.
- Cross-audience JWT rejection tests in both security planes.
- Platform route deny-by-default tests.
- Tenant lifecycle audit attribution tied to persisted operator ID.
- No change to legacy DB, frozen schema reference, existing tenant role grants, or tenant API v1 token semantics.
