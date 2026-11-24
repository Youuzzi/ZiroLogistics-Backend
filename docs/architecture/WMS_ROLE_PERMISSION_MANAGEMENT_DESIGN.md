# WMS Role & Permission Management Design

**Status:** Partially implemented; remaining policy and coverage gaps are tracked in `ROLE_BASED_ACCESS_CONTROL.md`.  
**Runtime:** WMS-only.  
**Schema:** Existing merged migrations are immutable. Any required schema change must be additive and justified by a concrete invariant.  
**Security:** Deny by default; tenant context and effective permissions are resolved from persisted membership state.

## 1. Purpose

Provide controlled tenant-scoped management of roles, permission grants, and membership-role assignments. The goal is to let an authorized tenant owner or delegated administrator manage access without allowing a tenant to modify the global permission catalogue or grant capabilities beyond its authority.

This stage is separate from tenant profile management and invitation issuance. It does not introduce platform-wide superuser behavior.

## 2. Existing persistence facts

The frozen V1 schema already contains:

- `roles`: tenant-scoped roles, unique by tenant and role code, with `is_system_role`, `status`, and optimistic `version`.
- `permissions`: global permission catalogue, identified by unique permission code and module.
- `role_permissions`: association between a role and global permission definitions.
- `membership_roles`: assignment of tenant roles to memberships, with tenant-aware composite relationships.
- `tenant_memberships`: user-to-tenant membership boundary.

The current authorization resolver calculates effective permissions from active user, membership, tenant, assigned active roles, and role-permission rows. It does not trust role or permission lists from JWT claims or client payloads.

The repository now contains role-management permissions and service/API functionality introduced by later migrations and implementation work. The permission catalogue and delegation policy are not yet complete for all WMS workflows. See the current source, migrations, tests, and `ROLE_BASED_ACCESS_CONTROL.md` for the implementation boundary.

## 3. Authorization model

Keep these concepts distinct:

- **Tenant capability / product entitlement:** whether a tenant's plan or enabled product modules allow a feature. This is not a role.
- **Role:** a tenant-scoped named bundle of permissions.
- **Permission:** a global, server-defined action code enforced by backend use cases.
- **Membership-role assignment:** grants a role to one membership inside the same tenant.

No client can create arbitrary permission codes. No client can submit a tenant ID to choose the authorization boundary. The current tenant is derived from the authenticated principal and re-resolved against the database for each protected operation.

## 4. Proposed management capabilities

The API surface should be implemented as separate use cases with explicit permissions:

| Capability | Proposed permission |
|---|---|
| List/read tenant roles | `WMS_ROLE_READ` |
| Create custom role | `WMS_ROLE_CREATE` |
| Update custom role metadata | `WMS_ROLE_UPDATE` |
| Deactivate custom role | `WMS_ROLE_DEACTIVATE` |
| Read permission catalogue | `WMS_PERMISSION_CATALOG_READ` |
| Replace custom role permission grants | `WMS_ROLE_PERMISSION_MANAGE` |
| Read membership role assignments | `WMS_MEMBERSHIP_ROLE_READ` |
| Assign/remove a role from a membership | `WMS_MEMBERSHIP_ROLE_MANAGE` |

These codes are an initial design vocabulary, not a complete statement of the current seeded catalogue. Verify each code against active Flyway migrations and enforcement tests before changing it. The role-management implementation exists, but the delegable catalogue is intentionally narrower than the full permission catalogue. Do not grant all management permissions to `TENANT_OWNER` merely because it is the initial role. The initial owner must receive only an explicitly reviewed allowlist.

## 5. Role lifecycle invariants

1. Every role is scoped to exactly one tenant.
2. Role lookup and mutation always constrain by both the resolved tenant ID and role public ID.
3. Tenant role codes are normalized, bounded, and unique within the tenant.
4. Clients cannot set `tenant_id`, `is_system_role`, role status, or version directly.
5. System roles cannot be renamed, deleted, or have protected grants changed through ordinary tenant APIs.
6. Custom roles may be edited only by a caller with the relevant permission.
7. Deactivation is preferred over physical deletion. A role assigned to memberships or referenced by audit history must not be hard-deleted.
8. Optimistic version is required for updates; stale writes return a conflict.
9. A tenant must not be left without an active owner path. The service must prevent deactivating or removing the last effective `TENANT_OWNER` assignment unless a replacement owner is committed in the same transaction.
10. All changes must be audited in the same transaction as the state mutation.

## 6. Permission grant invariants

1. Permission codes are selected only from the persisted global catalogue.
2. A role grant request is a complete desired set or an explicitly named delta; semantics must be consistent and documented.
3. The service validates the entire requested set before changing any grant.
4. A tenant administrator may grant only permissions that are within a server-defined grantable allowlist and within the caller's own authority.
5. Sensitive permissions (membership invitation, role management, owner administration, platform operations, billing/security controls) require explicit grant policy; never infer authority from role name alone.
6. Unknown, disabled, platform-only, or non-grantable permission codes are rejected without partial writes.
7. Existing effective permissions are recomputed from current DB state on the next request; do not copy permission grants into long-lived JWT claims.
8. A role cannot grant a capability that the tenant plan does not entitle. Entitlement enforcement remains a separate layer and must not be conflated with RBAC.

## 7. Membership-role assignment invariants

1. Membership and role must belong to the same resolved tenant; composite DB relationships remain the final integrity boundary.
2. Membership must be ACTIVE; user and tenant must be ACTIVE.
3. Role must be ACTIVE and belong to the same tenant.
4. A caller cannot assign a role with permissions that exceed the caller's delegation authority.
5. Prevent self-escalation and prevent granting/removing the last active tenant owner.
6. Assignment changes are idempotent and transactional.
7. Use row locks or a deterministic lock order for concurrent owner/role changes; optimistic version alone does not protect aggregate invariants.
8. Re-resolve effective permissions from the database after the transaction; do not issue new JWT claims containing role/permission snapshots.

## 8. Audit contract

Every successful role create/update/deactivate, permission grant replacement, and membership-role assignment change records an append-only tenant audit event in the same transaction.

Audit payloads should contain:
- tenant and subject public identifiers;
- actor membership reference;
- operation reference / idempotency key;
- event type;
- bounded before/after metadata or permission-code sets;
- timestamp.

Never record passwords, token secrets/digests, authorization headers, SMTP payloads, or other credentials. Failed authorization attempts may be logged through the security event channel but must not produce misleading successful mutation audit records.

If the current `tenant_audit_events` event allowlist cannot represent these operations, add an additive migration that expands the allowlist or introduces a normalized audit event type catalogue. Do not edit V8 after it has been merged.

## 9. HTTP and error behavior

Proposed base route: `/api/wms/v1/tenants/current`.

Candidate route groups:
- `/roles` — list, create, read, update, deactivate.
- `/permissions` — read-only permission catalogue filtered by grantability.
- `/roles/{rolePublicId}/permissions` — manage a custom role's permission set.
- `/memberships/{membershipPublicId}/roles` — read and manage assignments.

All routes require authenticated access-token principal and explicit permission checks. Use opaque public IDs, never expose internal numeric IDs. Return generic not-found behavior for cross-tenant object references to avoid confirming another tenant's resources. Validation errors must not reveal database diagnostics.

## 10. Concurrency and transaction strategy

- Lock the tenant row first for aggregate operations that affect owner invariants.
- Lock relevant membership and role rows in a documented deterministic order.
- Validate current state and authority after acquiring locks.
- Apply all role/grant/assignment changes and audit records in one transaction.
- Use optimistic role version for metadata and grant-set updates to detect stale clients.
- Add Testcontainers tests with concurrent transactions for duplicate role creation, grant replacement, and last-owner protection.
- Never hold a DB transaction while sending email or performing expensive password hashing.

## 11. Migration strategy

The V1 schema is frozen. Do not alter V1 or any merged migration V2–V8.

First inspect whether existing tables and constraints are sufficient. Prefer application-level invariants plus existing composite foreign keys when adequate. Add a new migration only for missing durable invariants, such as audit event allowlist support or a narrowly required role metadata field. Any migration must:
- use the WMS Flyway location;
- be additive and compatible with existing V1–V8 installations;
- avoid deprecated integer display widths;
- be validated with disposable MySQL 8.4 Testcontainers from both fresh and existing migration state;
- assert metadata, FK/check enforcement, and rollback behavior where applicable.

## 12. Required verification

- Cross-tenant role public IDs cannot be read or modified.
- Client-supplied tenant IDs are ignored/rejected.
- User, membership, tenant, or role inactive states deny mutation.
- A custom role cannot receive unknown or non-grantable permissions.
- System role protected grants cannot be changed through tenant APIs.
- Last active tenant owner cannot be removed or disabled without an atomic replacement.
- Stale version produces conflict and no partial audit/write.
- Concurrent assignments and grant changes preserve owner and tenant invariants.
- Audit write failure rolls back the business mutation.
- Permission changes take effect through DB-backed resolution without JWT re-issuance.
- HTTP tests cover authentication, exact permission gates, validation, and safe errors.

## 13. Explicit non-goals

- No public permission-catalogue mutation.
- No platform superuser or cross-tenant role administration endpoint.
- No role/permission data trusted from JWT claims.
- No tenant plan/capability implementation; that is a separate product-entitlement stage.
- No warehouse-level membership assignment until warehouse authorization requirements and schema are designed.
- No physical deletion of roles or audit evidence.
- No production database access or mutation.
