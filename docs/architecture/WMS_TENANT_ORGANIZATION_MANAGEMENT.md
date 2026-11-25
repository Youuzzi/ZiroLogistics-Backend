# WMS Tenant & Organization Management

**Status:** Tenant profile API, repeatable tenant provisioning, controlled lifecycle command, and transactional tenant audit are implemented. Authenticated platform HTTP lifecycle administration is not yet implemented.
**Schema baseline:** Frozen V1 with additive tenant migrations V7 and V8; later WMS migrations add permission and role-template policy without rewriting historical migrations.
**Security boundary:** Tenant profile operations use tenant membership authorization. Provisioning and lifecycle remain explicit non-web operator commands; caller-supplied operator references are audit attribution, not authenticated platform identities.

## 1. Organization model

A tenant is the WMS organization boundary. It owns:
- globally unique immutable `tenant_code`;
- legal name and display name;
- lifecycle status (`ACTIVE`, `SUSPENDED`, `CLOSED`);
- IANA timezone;
- ISO-4217 default currency;
- optimistic-lock version and timestamps.

A tenant is not a warehouse, branch, store, or physical facility. A global user may hold multiple tenant memberships; each tenant operation resolves the selected membership against current database state.

## 2. Profile API

Base route: `/api/wms/v1/tenants/current/profile`

| Method | Route | Access | Purpose |
|---|---|---|---|
| GET | `/api/wms/v1/tenants/current/profile` | Authenticated active tenant membership | Read the selected tenant profile |
| PUT | `/api/wms/v1/tenants/current/profile` | `WMS_TENANT_PROFILE_UPDATE` | Replace editable profile fields |

The tenant is derived from the authenticated server-side principal; clients cannot select a tenant by supplying a tenant ID.

Editable fields:
- `legalName` (2–200 characters);
- `displayName` (2–150 characters);
- `timezone` (valid IANA `ZoneId`);
- `defaultCurrency` (supported ISO-4217 code).

The API does not allow clients to change `tenantCode`, `status`, `publicId`, `createdAt`, or `version`. Updates use `expectedVersion`; stale writes conflict and must be retried after reloading. JPA `@Version` remains the final concurrent-write guard.

## 3. Tenant authorization

Before profile access, the service re-resolves the caller's user and membership through `WmsMembershipAccessService`. It compares tenant, membership, and public identifiers with the authenticated principal and uses persisted effective permissions, not caller-supplied permission lists.

Tenant owners cannot change tenant lifecycle status through the profile API. Tenant lifecycle changes are a separate platform operation; no public self-service registration or tenant status endpoint is provided.

## 4. Tenant audit

Migration V8 creates `tenant_audit_events` with tenant-member/operator attribution, tenant provisioning and lifecycle event types, and unique idempotent operation references.

A profile update writes one `TENANT_PROFILE_UPDATED` event in the same transaction as the tenant update. The event includes tenant/subject public identifiers, active same-tenant actor membership, before/after profile values, timestamp, and event public ID. If audit insertion fails, the profile update rolls back. Passwords, action tokens, credentials, and email payloads are not recorded.

Events distinguish:
- `TENANT_MEMBER`: actor membership is linked to the same tenant;
- `PLATFORM_OPERATOR`: an operator reference is recorded without a tenant membership ID.

The second value is an audit actor type, not proof that the reference maps to an authenticated platform operator. Production database grants should deny UPDATE and DELETE on audit tables to the runtime role.

## 5. Provisioning and bootstrap paths

### 5.1 First-install controlled bootstrap

The first-install bootstrap is a one-time, explicit non-web command guarded by a singleton database state row and fresh-database checks. It creates:
- the initial tenant;
- an active global WMS user with verified email;
- an active tenant membership;
- the tenant-scoped `TENANT_OWNER` role;
- the canonical Tenant Owner permission set;
- the 13 tenant operational system-role templates;
- the completed bootstrap marker.

The operation is transactional and refuses non-empty installations or repeat execution. It does **not** create a global platform operator or platform administrator. Bootstrap credentials are supplied out-of-band and must not be committed, logged, or embedded in an image.

### 5.2 Repeatable tenant provisioning

New tenants are created only through the explicitly enabled non-web operator command; there is no public registration or tenant-creation endpoint.

Required configuration:
- `WMS_TENANT_PROVISIONING_ENABLED`
- `WMS_TENANT_PROVISIONING_TENANT_CODE`
- `WMS_TENANT_PROVISIONING_LEGAL_NAME`
- `WMS_TENANT_PROVISIONING_DISPLAY_NAME`
- `WMS_TENANT_PROVISIONING_TIMEZONE`
- `WMS_TENANT_PROVISIONING_CURRENCY`
- `WMS_TENANT_PROVISIONING_OWNER_EMAIL`
- `WMS_TENANT_PROVISIONING_OWNER_FULL_NAME`
- `WMS_TENANT_PROVISIONING_OPERATION_REF`
- `WMS_TENANT_PROVISIONING_OPERATOR_REFERENCE`
- `WMS_TENANT_PROVISIONING_REASON`

Provisioning creates the tenant, owner membership, `TENANT_OWNER` role, canonical Owner grants, 13 operational system-role templates, and a transactional tenant audit event. Both first-install bootstrap and repeatable provisioning use `WmsTenantPermissionPolicy` and `WmsTenantRoleTemplateProvisioner`; they do not maintain separate owner allowlists.

For a new owner, provisioning creates a `PENDING_VERIFICATION` global identity with a random Argon2id hash that is never disclosed. The pending identity cannot authenticate. After the tenant transaction commits, verification is requested through the encrypted outbox; the owner verifies the address and establishes a password through the existing password-reset lifecycle. An existing global identity is reused only if ACTIVE and email-verified; it is never duplicated.

The operation reference supports idempotent retry for the same tenant and owner input. Reuse for a different operation or inconsistent input is rejected. The owner email and credentials are not included in audit payloads or command output. Outbox queueing is not delivery; SMTP remains disabled until explicitly configured.

## 6. Controlled tenant lifecycle

Tenant lifecycle status is not exposed through tenant-facing HTTP APIs. The current lifecycle command supports:
- `ACTIVE -> SUSPENDED`;
- `SUSPENDED -> ACTIVE`;
- `SUSPENDED -> CLOSED`.

It requires explicit command mode and enablement, action, tenant public ID, reason (10–500 characters), unique UUID operation reference, and operator reference. Closure additionally requires exact confirmation `CLOSE:<tenant_code>`. Simultaneous bootstrap and lifecycle command modes are refused.

The service locks the tenant row, checks the source state, applies the state/version update, and inserts the tenant audit event in one transaction. A repeated operation reference for the same tenant and action returns the prior result; reuse for another target/action is rejected. Direct `ACTIVE -> CLOSED` is rejected, and `CLOSED` is terminal. Closure retains historical records; it is not physical deletion.

**Important security limitation:** the command runner passes the configured operator-reference string to the lifecycle service. The service validates its syntax and stores it as `actor_reference`, but does not resolve it to a persisted platform operator or verify an authenticated principal. This is suitable only as a controlled offline/deployment attribution mechanism; it must not be represented as authenticated platform authorization or exposed as a web endpoint.

## 7. Remaining work before authenticated platform lifecycle APIs

1. Complete the isolated platform HTTP security boundary and persisted platform authorization evaluator.
2. Bind every lifecycle command/API actor to an authenticated platform operator or a separately verified machine identity; never trust a free-form operator label as authorization.
3. Define exact platform permissions for tenant provisioning, suspension, reactivation, and closure.
4. Add negative tests for missing/inactive operators, missing grants, wrong scopes, duplicate operation references, invalid state transitions, and cross-tenant target substitution.
5. Record platform audit and tenant audit consistently, including authenticated operator ID, permission used, reason, target tenant, request/correlation ID, and outcome.
6. Keep support access and SaaS entitlements separate from tenant lifecycle and tenant WMS RBAC.

No public self-registration, default tenant credentials, platform god-mode, or tenant-facing lifecycle mutation is introduced.
