# WMS Tenant, Identity, and Membership Foundation

**Status:** Initial persistence slice implemented; authentication and authorization enforcement are not yet implemented.  
**Schema:** Frozen WMS V1 migration `classpath:db/wms-migration`  
**Runtime:** `WmsRuntimeApplication` under the `wms` profile

## 1. Scope of this slice

This implementation maps the first WMS core tables into the bounded WMS runtime:

- `tenants`
- `users`
- `tenant_memberships`
- `roles`
- `permissions`
- `membership_roles`
- `role_permissions`

It adds Spring Data repositories and a MySQL 8.4 Testcontainers integration test that starts from the frozen V1 migration and asks Hibernate to validate mappings.

This slice deliberately does **not** expose public registration, login, tenant provisioning, invitation, role assignment, permission evaluation, or JWT issuance endpoints. Those are separate security-sensitive use cases and must be designed before exposing APIs.

## 2. Schema facts preserved

- A user is a global identity: `users.email` is globally unique.
- A tenant is an organization boundary: `tenants.tenant_code` is globally unique.
- A user joins a tenant through `tenant_memberships`; the pair `(tenant_id, user_id)` is unique.
- Membership status is one of `INVITED`, `ACTIVE`, `SUSPENDED`, or `REMOVED`.
- A role belongs to one tenant and is unique by `(tenant_id, role_code)`.
- Permission definitions are a global catalogue; permission codes are globally unique.
- `membership_roles` ties a membership to a role and carries `tenant_id` so the database's composite foreign keys enforce same-tenant assignment.
- `role_permissions` links roles to global permissions.

The schema does not contain a warehouse-to-membership assignment table. This implementation therefore makes no claim of per-warehouse user authorization.

## 3. Runtime database safety

The WMS profile uses only:

- `WMS_DB_URL`
- `WMS_DB_USERNAME`
- `WMS_DB_PASSWORD`
- `WMS_FLYWAY_ENABLED`

It does not fall back to the legacy `DB_URL`, `DB_USERNAME`, or `DB_PASSWORD` variables. WMS Flyway is disabled by default and points only to `classpath:db/wms-migration`. Hibernate is configured to `validate`, never to create or update the schema.

For local runtime startup, set WMS-specific credentials only after verifying that the URL points to a dedicated, empty WMS database. Enable `WMS_FLYWAY_ENABLED=true` only for an intentional fresh-database bootstrap. Do not point this runtime at `zirologistics_db`.

## 4. Persistence design notes

- JPA entity and repository scanning is bounded to `com.zirocraft.zirologistics.wms`.
- Tenant and membership references are represented by scalar IDs in this initial mapping; database foreign keys remain authoritative for referential and tenant-consistency constraints.
- Public IDs are generated as UUID strings before insert.
- Optimistic version columns are mapped where present in the frozen schema.
- Status fields use enums matching the schema CHECK constraint values.
- Fixed-width `CHAR` identifiers/currency columns are explicitly declared to support schema validation.
- The permission catalogue and tenant role assignments are separate concepts; a role is not itself a permission.

## 5. Security work still required

Before any WMS endpoint is exposed, implement and test:

1. Global identity authentication and password-hash policy.
2. Explicit tenant/membership selection for users with multiple active memberships.
3. Revalidation of user and membership status on authenticated requests.
4. Tenant context derived from verified server-side membership, never from an untrusted tenant ID.
5. Permission resolution through active membership roles and role permissions.
6. Backend object-level authorization and tenant-scoped repository access.
7. Token expiry, refresh, revocation, tenant switching, and stale-claim semantics.
8. Safe onboarding/invitation and privileged role assignment workflows.
9. Audit events for membership and authorization changes.

Do not treat the presence of these entities/repositories as proof that any of these controls are enforced.

## 6. Verification

The integration test `WmsTenantIdentityPersistenceIntegrationTest`:

- starts a disposable MySQL 8.4 Testcontainers database;
- applies the frozen WMS V1 Flyway migration;
- validates the mapped schema with Hibernate `ddl-auto=validate`;
- persists two tenants and one global user;
- verifies that the same user can have separate memberships in both tenants;
- verifies tenant-scoped membership lookup does not return a membership under the wrong tenant;
- persists a tenant role, a global permission, and their membership/role assignments.

A green CI run is required before merge. The test does not connect to or modify a developer database.
