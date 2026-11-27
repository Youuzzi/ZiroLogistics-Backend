# Stage 16 — Platform Control-Plane Source Review (2026-11-27)

**Baseline:** `e88a3b9fbbdd5aeef31c02a542c46241a648f22f`
**Scope:** Platform JWT authentication, current persisted permission evaluation, tenant lifecycle application boundary, and support-grant governance.
**Status:** Source review with referenced tests; not a certification or complete route-to-query audit.

## Reviewed implementation

### Platform authentication and authorization
- `PlatformSecurityConfiguration` installs an ordered, dedicated stateless filter chain for `/api/platform/v1/**`; requests in that matcher require authentication.
- `PlatformJwtAuthenticationConverter` requires a nonblank operator subject, integral credential-version claim, explicit MFA claim and MFA timestamp; it resolves the operator's current active and email-verified state and credential version from persistence on each request.
- The converter creates a platform principal with no authorities. The source comment explicitly defers permission loading; application services must use the persisted permission evaluator.
- The platform decoder uses its own configuration namespace, RSA key-material bean, issuer, audience, `token_use=platform_access`, and key-ID validation. The tenant decoder has separate tenant-selection/access audiences and token-use profiles. Configuration separation is present in source; deployed PEM values must still be verified to ensure the actual signing keys are distinct.
- Platform MFA freshness is checked at two levels: no older than 15 minutes when converting the bearer token, then no older than five minutes when authorizing HIGH/CRITICAL actions.
- `PlatformPermissionEvaluator` denies malformed inputs, missing grants, unknown risk values, and data-access failures. It reads current active operator/role/permission assignments. HIGH and CRITICAL permissions require MFA no older than five minutes at use time.

### Tenant lifecycle
- `PlatformTenantLifecycleApplicationService` derives the actor from the Spring Security context rather than accepting an actor ID from the request.
- It maps each lifecycle action to a distinct platform permission, invokes the tenant lifecycle service, and writes a platform audit event for a state transition that was not already applied.
- The service fails closed when the caller is not a platform principal or current permission evaluation rejects the action.

### Support-grant governance
- `PlatformSupportGrantService` requires persisted platform permissions for request, approve/reject, and revoke operations.
- Grants are limited to four hours and a fixed read-only scope allowlist: tenant metadata, tenant configuration, integration diagnostics, and tenant audit metadata.
- Approval requires a different operator from the requester and an active tenant; grant state changes are conditional and audited.
- Grant request, approval, rejection, and revocation use row locking and conditional state updates.

## Test evidence located
- `PlatformJwtAuthenticationConverterTest`
- `PlatformPermissionEvaluatorTest`
- `PlatformPermissionEvaluatorMySqlIntegrationTest`
- `PlatformPermissionRiskMySqlIntegrationTest`
- `PlatformSupportGrantServiceTest`
- `PlatformSupportGrantGovernanceMySqlIntegrationTest`
- `PlatformTenantLifecycleApplicationServiceTest`

Test names and source presence are evidence pointers, not proof that every production route or failure mode is covered.

## Gaps and follow-up work
1. No platform HTTP controller was found in the reviewed `src/main/java` tree. The platform filter chain currently protects the namespace, but the reviewed application services are not yet exposed through a complete HTTP route surface.
2. No support-grant consumer/enforcement path was found in the reviewed source tree. Governance records and approval alone must never be interpreted as granting access; access must remain unavailable until a separate, explicit, tenant-scoped, purpose-bound, time-bound and audited enforcement boundary exists.
3. Add negative HTTP boundary tests proving tenant JWTs cannot authenticate platform routes and platform JWTs cannot authenticate tenant operational routes.
4. Add MySQL-backed tests for support-grant expiry, not-yet-started grants, revocation races, tenant suspension after approval, scope enforcement at every consumer, and audit-write rollback.
5. Add runtime configuration tests proving platform and tenant deployments use distinct RSA key material, issuer and audience values; source has separate beans/namespaces, but environment configuration can still accidentally reuse PEM values.
6. Confirm all platform mutation entry points remain protected when called outside HTTP, and that audit persistence failure rolls back the corresponding state transition.

## Conclusion
No concrete platform-to-tenant privilege escalation was confirmed in this source slice. The reviewed code shows useful separation and deny-by-default patterns, but the absence of a support-grant consumer means support access is not demonstrated as implemented. This slice is source-reviewed, not production certification.

## Evidence labels
- **Source reviewed:** source inspected without full runtime proof.
- **MySQL integration verified:** actual MySQL behavior exercised by the referenced test.
- **HTTP boundary verified:** request/authentication/principal forwarding exercised.
- **Not covered:** no sufficient evidence.
