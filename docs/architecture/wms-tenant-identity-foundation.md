# WMS Tenant, Identity, and Membership Foundation

**Status:** V1 persistence foundation retained; identity lifecycle, authentication, membership resolution, and invitation flows are implemented in subsequent WMS slices.  
**Schema:** Frozen WMS V1 migration `classpath:db/wms-migration`  
**Runtime:** `WmsRuntimeApplication` under the `wms` profile

## 1. Scope

This document records the original persistence foundation for:
- `tenants`
- `users`
- `tenant_memberships`
- `roles`
- `permissions`
- `membership_roles`
- `role_permissions`

The initial mapping and Testcontainers validation were the starting point for later identity, authentication, invitation, and authorization implementation. It must not be read as a current statement that those later features are absent.

## 2. Schema invariants

- A user is a global identity: `users.email` is globally unique.
- A tenant is an organization boundary: `tenants.tenant_code` is globally unique.
- A user joins a tenant through `tenant_memberships`; `(tenant_id, user_id)` is unique.
- Membership status is one of `INVITED`, `ACTIVE`, `SUSPENDED`, or `REMOVED`.
- Tenant status is one of `ACTIVE`, `SUSPENDED`, or `CLOSED`.
- A role belongs to one tenant and is unique by `(tenant_id, role_code)`.
- Permission definitions are a global catalogue; permission codes are globally unique.
- `membership_roles` carries `tenant_id`, allowing composite foreign keys to enforce same-tenant assignment.
- `role_permissions` links tenant roles to global permission definitions.
- No warehouse-to-membership assignment table exists in V1; this foundation makes no claim of per-warehouse user authorization.

## 3. Runtime database safety

The WMS profile uses only `WMS_DB_URL`, `WMS_DB_USERNAME`, `WMS_DB_PASSWORD`, and `WMS_FLYWAY_ENABLED`. It does not fall back to legacy `DB_URL`, `DB_USERNAME`, or `DB_PASSWORD`. WMS Flyway is disabled by default and points only to `classpath:db/wms-migration`; Hibernate is configured to validate, never create or update the schema.

Never point the WMS runtime or migrations at the legacy `zirologistics_db`. All CI integration tests use disposable MySQL 8.4 Testcontainers databases.

## 4. Persistence boundaries

- JPA entity and repository scanning is bounded to `com.zirocraft.zirologistics.wms`.
- Tenant and membership references may be represented by scalar IDs; database foreign keys remain authoritative for referential and tenant-consistency constraints.
- Public IDs are generated as UUID strings before insert.
- Optimistic version columns are mapped where present in the frozen schema.
- Status fields use enums matching schema CHECK constraints.
- The permission catalogue and tenant role assignments remain separate concepts; a role is not itself a permission.

## 5. Related implementation contracts

- `wms-tenant-context-and-authorization-resolution.md` — server-side membership and effective permission resolution.
- `wms-authentication-http-api-contract.md` — opt-in HTTP authentication and account lifecycle routes.
- `wms-controlled-platform-bootstrap.md` — controlled first-install tenant-owner creation.
- `wms-tenant-organization-management.md` — tenant profile, permission, and audit behavior.

