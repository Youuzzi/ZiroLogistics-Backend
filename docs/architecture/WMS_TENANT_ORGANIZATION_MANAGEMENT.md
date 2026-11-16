# WMS Tenant & Organization Management

**Status:** Tenant profile API and transactional profile audit implemented in the current feature branch.  
**Schema baseline:** Frozen V1; additive migrations V7 and V8 only.  
**HTTP activation:** Requires the existing explicit WMS JWT and HTTP identity endpoint switches. The routes remain deny-by-default when those switches are absent.

## 1. Organization model

A tenant is the WMS organization boundary. The tenant record owns:
- globally unique immutable `tenant_code`;
- legal name and display name;
- lifecycle status (`ACTIVE`, `SUSPENDED`, `CLOSED`);
- IANA timezone;
- ISO-4217 default currency;
- optimistic-lock version and timestamps.

A tenant is not a warehouse, branch, store, or physical facility. Warehouse and location management remains a separate master-data stage. A global user may hold multiple tenant memberships; every operation resolves the authenticated user's selected membership against current database state.

## 2. Profile API

Base route: `/api/wms/v1/tenants/current/profile`

| Method | Route | Access | Purpose |
|---|---|---|---|
| GET | `/api/wms/v1/tenants/current/profile` | Authenticated active tenant membership | Read the selected tenant's profile |
| PUT | `/api/wms/v1/tenants/current/profile` | `WMS_TENANT_PROFILE_UPDATE` | Replace editable profile fields |

The current tenant is derived from the authenticated server-side principal. There is no tenant ID or tenant public ID in the request path or body.

Editable fields:
- `legalName` (2–200 characters);
- `displayName` (2–150 characters);
- `timezone` (valid IANA `ZoneId`);
- `defaultCurrency` (supported ISO-4217 code).

The API does not allow clients to change `tenantCode`, `status`, `publicId`, `createdAt`, or `version`. The caller supplies `expectedVersion`; stale updates return a conflict and must be retried after reloading the profile. JPA `@Version` remains the final concurrent-write guard.

## 3. Authorization

Migration V7 seeds the global catalogue permission `WMS_TENANT_PROFILE_UPDATE`. The controlled bootstrap's tenant owner receives this permission alongside `WMS_IDENTITY_MEMBERSHIP_INVITE`; no wildcard or module-wide grant is introduced.

Before profile access, the service re-resolves the caller's user and membership through `WmsMembershipAccessService`. It compares tenant, membership, and public identifiers with the authenticated principal and uses the persisted effective permission set, not a caller-supplied permission list.

Tenant owners cannot change tenant lifecycle status through this API. Tenant suspension, reactivation, and closure are platform-operator operations and require a separate controlled lifecycle workflow. No public self-service tenant registration or tenant status endpoint is provided.

## 4. Audit

Migration V8 creates `tenant_audit_events`. A profile update writes one `TENANT_PROFILE_UPDATED` event in the same transaction as the tenant row update. The event includes:
- tenant and subject public identifiers;
- active actor membership scoped to the same tenant;
- before/after values for the editable profile fields;
- event timestamp and generated event public ID.

The audit insert is performed only when the persisted actor user and membership remain active and belong to the same tenant. If the audit insert fails, the profile update rolls back. No passwords, action tokens, credentials, or email payloads are recorded.

The table is append-only at the application layer. Production database grants must deny UPDATE and DELETE to the runtime role as part of deployment hardening.

## 5. Migrations

- V7: seeds `WMS_TENANT_PROFILE_UPDATE`.
- V8: creates `tenant_audit_events` with tenant and composite membership foreign keys.

Do not edit V1–V6. All schema verification uses MySQL 8.4 Testcontainers; never apply migrations to a user or production database as part of development tests.

## 6. Verification requirements

The tenant profile MySQL integration test must verify:
- only the selected active membership can resolve its tenant;
- profile update is denied without the persisted permission;
- a forged cross-tenant principal is rejected;
- tenant code and lifecycle status cannot be changed by the profile contract;
- valid timezone/currency and field lengths are enforced;
- optimistic version conflicts are rejected;
- profile update and audit event commit atomically;
- audit event contains before/after values and is tenant-scoped;
- failed validation or authorization leaves both profile and audit unchanged.

## 7. Remaining tenant lifecycle work

This slice deliberately does not implement platform-operator tenant suspension, reactivation, closure, or repeatable tenant provisioning. Those operations must use an explicit non-web operator workflow, audited reason codes, idempotent operation references, and safe state transitions:
- `ACTIVE -> SUSPENDED`;
- `SUSPENDED -> ACTIVE`;
- `SUSPENDED -> CLOSED`.

Hard deletion is not a tenant lifecycle operation. Closed tenants retain their historical records for audit and referential integrity. The lifecycle command must reject direct `ACTIVE -> CLOSED` transitions and must never accept a tenant ID from an HTTP caller.
