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

Migration V8 creates `tenant_audit_events` with tenant-member/operator attribution, tenant provisioning and lifecycle event types, and unique idempotent operation references. A profile update writes one `TENANT_PROFILE_UPDATED` event in the same transaction as the tenant row update. The event includes:
- tenant and subject public identifiers;
- active actor membership scoped to the same tenant;
- before/after values for the editable profile fields;
- event timestamp and generated event public ID.

The audit insert is performed only when the persisted actor user and membership remain active and belong to the same tenant. If the audit insert fails, the profile update rolls back. No passwords, action tokens, credentials, or email payloads are recorded.

The table is append-only at the application layer. Events distinguish `TENANT_MEMBER` actors (with a same-tenant membership FK) from `PLATFORM_OPERATOR` actors (with a required operator reference and no membership ID). Lifecycle events carry a unique operation reference so retries are idempotent. Production database grants must deny UPDATE and DELETE to the runtime role as part of deployment hardening.

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

## 7. Repeatable operator tenant provisioning

New tenants are created only through the non-web operator command; there is no public registration or tenant-creation endpoint. The command requires `--wms.tenant.provisioning.command-mode=true` and `WMS_TENANT_PROVISIONING_ENABLED=true`. The application entry point switches to non-web mode, and the runner is excluded from servlet runtimes.

Required configuration:
- `WMS_TENANT_PROVISIONING_ENABLED`
- `WMS_TENANT_PROVISIONING_TENANT_CODE`
- `WMS_TENANT_PROVISIONING_LEGAL_NAME`
- `WMS_TENANT_PROVISIONING_DISPLAY_NAME`
- `WMS_TENANT_PROVISIONING_TIMEZONE`
- `WMS_TENANT_PROVISIONING_CURRENCY`
- `WMS_TENANT_PROVISIONING_OWNER_EMAIL`
- `WMS_TENANT_PROVISIONING_OWNER_FULL_NAME`
- `WMS_TENANT_PROVISIONING_OPERATION_REF` (unique UUID for idempotent retry)
- `WMS_TENANT_PROVISIONING_OPERATOR_REFERENCE`
- `WMS_TENANT_PROVISIONING_REASON`

Provisioning creates the tenant, an active membership, a tenant-scoped `TENANT_OWNER` role, and only the explicit invitation/profile-update permission allowlist. A globally existing owner must already be ACTIVE and email-verified; the existing global identity is reused and never duplicated.

For a new owner, the command creates a `PENDING_VERIFICATION` global identity with a random Argon2id hash that is never disclosed. The membership and role are prepared transactionally, but the pending global identity cannot authenticate. After the transaction commits, the command requests an email-verification message through the encrypted outbox. The owner verifies the address, then requests a password reset to establish their own password. If email encryption/outbox configuration is unavailable, the command reports failure after the tenant transaction; rerunning with the same operation reference safely reuses the tenant and retries the verification email. Queueing is not delivery: the SMTP worker remains disabled by default and must be enabled with its explicit SMTP/TLS configuration before production email is sent.

The operation reference is unique in the tenant audit table. A retry with the same operation reference, tenant code, and owner email returns the previously provisioned identifiers. Reuse with different input or for another operation is rejected. The owner email is not included in the audit payload or command output.

## 8. Controlled tenant lifecycle

Tenant lifecycle status is not exposed through the tenant-facing HTTP API. It is changed only through the explicit non-web operator command:
- `ACTIVE -> SUSPENDED`;
- `SUSPENDED -> ACTIVE`;
- `SUSPENDED -> CLOSED`.

The command requires `--wms.tenant.lifecycle.command-mode=true`, `WMS_TENANT_LIFECYCLE_ENABLED=true`, an action, tenant public ID, reason (10–500 characters), unique UUID operation reference, and operator reference (3–120 safe identifier characters). Closing additionally requires exact confirmation `CLOSE:<tenant_code>`. The application entry point switches to non-web mode for this command and refuses simultaneous bootstrap and lifecycle command modes.

Configuration uses:
- `WMS_TENANT_LIFECYCLE_ENABLED`
- `WMS_TENANT_LIFECYCLE_ACTION` (`SUSPEND`, `REACTIVATE`, or `CLOSE`)
- `WMS_TENANT_LIFECYCLE_TENANT_PUBLIC_ID`
- `WMS_TENANT_LIFECYCLE_REASON`
- `WMS_TENANT_LIFECYCLE_OPERATION_REF`
- `WMS_TENANT_LIFECYCLE_OPERATOR_REFERENCE`
- `WMS_TENANT_LIFECYCLE_CONFIRMATION` (required only for closure)

The operator reference is recorded in the audit event; it should identify the authenticated operator or controlled deployment identity and must not be a secret. A repeated operation reference for the same tenant and action returns the previously applied result. Reusing it for another tenant or action is rejected. The service locks the tenant row, checks the expected source state, updates status/version, and inserts the audit event in one transaction.

Direct `ACTIVE -> CLOSED` is rejected. `CLOSED` is terminal. Hard deletion is not a tenant lifecycle operation; closed tenants retain historical records for audit and referential integrity. No tenant ID or status transition is accepted from a public HTTP caller.

Tenant profile, repeatable onboarding, and operator lifecycle operations are all controlled separately; none exposes public tenant registration or client-directed tenant status changes.
