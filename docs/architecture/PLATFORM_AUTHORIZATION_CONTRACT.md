# Platform Authorization Contract

**Status:** Target authorization contract with partial service-layer enforcement implemented. Persisted platform roles/permissions, `PlatformPermissionEvaluator`, operator-role administration, support-grant governance, and platform tenant-lifecycle authorization exist; a complete HTTP endpoint surface and support-grant scope consumer remain incomplete.

## 1. Current implementation boundary

- `PlatformPermissionEvaluator` reads current persisted operator-role-permission grants and denies on missing grants or data-access failure. HIGH/CRITICAL permissions require MFA no older than five minutes at use time.
- `PlatformOperatorRoleAdministrationService` enforces actor permission, delegation ceiling, no self-assignment, no routine `PLATFORM_OWNER` assignment, and audit writes.
- `PlatformTenantLifecycleApplicationService` derives the actor from Spring Security and checks action-specific platform permissions before lifecycle transitions.
- V30 and `PlatformSupportGrantService` implement request/approve/reject/revoke governance with a four-hour maximum and a fixed read-only scope allowlist. A tenant data-service consumer that enforces those scopes is not established.
- No platform HTTP controllers were found in the current source tree; service-layer tests do not prove a complete externally reachable platform API.

## 2. Security boundary

Platform authorization is independent from tenant WMS RBAC and SaaS plan entitlements.

- A platform principal identifies exactly one persisted platform operator.
- A platform principal has no tenant ID, tenant membership, warehouse, or tenant role context.
- Tenant roles and permissions must never authorize platform endpoints.
- Platform roles and permissions must never authorize tenant inventory or warehouse operations.
- Subscription entitlements describe what a tenant's plan may use; they are not operator permissions and cannot grant administrative authority.
- A valid platform JWT proves authentication only. It does not, by itself, grant a platform permission.

The platform request principal remains tenant-free and must be revalidated against current operator status and credential version on every authenticated request.

## 3. Permission model

Use explicit, stable permission keys in the platform control plane. Permission keys describe an action on a platform resource, not a UI screen or a job title. Examples for future endpoint design include:

- `platform.operator.read`
- `platform.operator.invite`
- `platform.operator.suspend`
- `platform.role.read`
- `platform.role.assign`
- `platform.tenant.read`
- `platform.tenant.lifecycle.manage`
- `platform.support.grant`
- `platform.audit.read`

These examples are vocabulary proposals, not active grants. A permission is not usable until its endpoint/use case, scope, audit requirements, and tests are implemented and reviewed.

Authorization is deny-by-default:
1. Authenticate the platform token using the dedicated platform decoder.
2. Revalidate operator existence, ACTIVE status, verified email, credential version, and required MFA freshness.
3. Load current persisted platform role assignments and their permission grants; never trust role or permission lists supplied by the token or request.
4. Evaluate the exact required permission for the operation.
5. Apply any resource-level scope and lifecycle constraints.
6. Record the security-relevant action in the platform audit trail.
7. Deny when the permission is missing, the scope is invalid, data is unavailable, or policy evaluation fails.

No wildcard permission, implicit superuser role, or "all permissions" fallback is allowed.

## 3. Role assignment and delegation

- Roles are bundles of reviewed permission keys; they are not authentication identities.
- Assignments must refer to persisted platform operators and persisted platform roles.
- A caller may not grant a permission they do not hold or delegate authority beyond their own grantable scope.
- Role assignment, revocation, and changes to role-permission mappings are privileged, auditable actions.
- Prevent self-escalation: an operator cannot use a role assignment path to grant themselves additional privilege.
- Protect the final active operator / recovery path with an explicit lifecycle policy; do not silently allow removal of all viable platform governance.
- System-defined platform roles and their permission mappings must be versioned and changed through reviewed migrations or controlled administrative workflows, not arbitrary client payloads.
- Custom roles, if introduced, must be constrained by a grantable-permission policy and cannot contain reserved platform governance permissions by default.

Do not assume a platform role can be combined with another role on one operator unless the persistence and evaluation model explicitly supports and tests that behavior.

## 4. Scope and support access

Platform operator permissions are not a blanket authorization to inspect tenant business data.

- Tenant metadata administration and tenant business-data access are separate permission domains.
- Any future support access to tenant data must be explicit, time-limited, tenant-scoped, purpose-bound, approved according to policy, and fully audited.
- Support access must not silently inherit a platform operator's ordinary administrative permissions.
- No tenant impersonation or unrestricted support mode is part of this authorization slice.
- Every support grant must have a defined start, expiry, revocation path, reason, actor, target tenant, and audit record before implementation.

## 5. MFA and high-risk operations

High-risk permissions require recent, successfully completed MFA. The endpoint/use-case policy must identify high-risk operations explicitly; examples include operator invitation, suspension, role assignment, permission catalogue changes, tenant lifecycle actions, support grants, and credential recovery.

- MFA enrollment state alone is not proof of a recent MFA challenge.
- A token's MFA marker must satisfy the platform token contract and freshness window.
- Sensitive operations should require step-up MFA if the authentication age exceeds the operation-specific threshold.
- Recovery must not become a bypass around role governance or audit controls.

## 6. Audit and failure behavior

Audit events for privileged actions should capture the acting operator public ID, action/permission key, target resource identifiers, outcome, reason where required, correlation/request ID, and UTC occurrence time. Never record passwords, access tokens, MFA secrets, recovery codes, or other authentication secrets.

Authorization failures must not reveal whether another operator, tenant, or protected resource exists. Fail closed if current grants cannot be loaded or evaluated.

## 7. Endpoint implementation requirements

Before exposing each platform endpoint:
- Declare its exact permission key and whether MFA step-up is required.
- Define resource scope and permitted state transitions.
- Ensure the authorization check occurs in the application/service boundary as well as at the HTTP route boundary where appropriate.
- Add positive tests for an authorized operator and negative tests for missing permission, wrong role, inactive operator, stale credential version, missing/stale MFA, cross-tenant identifier attempts, and self-escalation where relevant.
- Verify audit event creation for successful and denied privileged actions.
- Verify that tenant JWTs cannot enter platform routes and platform JWTs cannot enter tenant routes.
- Keep the route inaccessible until the permission catalogue, persisted grants, and policy tests are present.

## 8. Delivery sequence

1. Preserve the dedicated platform authentication and HTTP boundary.
2. Implement the canonical permission catalogue and persistence read model from the existing platform role/permission schema.
3. Implement a fail-closed authorization evaluator and method-level policy integration.
4. Add role/permission assignment lifecycle with anti-escalation and audit controls.
5. Add endpoint permissions incrementally, only alongside their endpoint implementation and tests.
6. Add narrowly scoped tenant lifecycle operations.
7. Design support access as a separate, time-limited authorization capability.
8. Keep first-operator bootstrap and credentials behind explicit user approval.

## 9. Non-goals

This contract does not implement billing, payment processing, tenant plan entitlements, operator login/MFA enrollment endpoints, tenant impersonation, support data access, or first-operator bootstrap. It does not grant permissions to any operator and does not make the platform security chain enabled by default.
