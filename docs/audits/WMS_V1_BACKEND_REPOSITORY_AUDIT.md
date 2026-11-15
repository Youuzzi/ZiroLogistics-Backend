# WMS V1 Backend Repository Audit

- **Status:** Initial repository audit completed; implementation refactor not started.
- **Audited branch:** `main`
- **Audited commit:** `b9386b1107b6bcdd2b16d5bf91d8c733e7e6340b`
- **Repository snapshot:** 98 tracked files.
- **Target:** Tenant-based ZiroLogistics WMS V1.
- **Authoritative schema:** `docs/database/source/zirowms_v1_schema.sql`
- **Schema migration:** `classpath:db/wms-migration`

## 1. Executive assessment

The repository currently contains two distinct generations of backend design:

1. A working legacy, single-organization WMS implementation based on `users`, `warehouses`, `bins`, `items`, `inventory_stocks`, and `inventory_ledger`.
2. The frozen tenant-based WMS V1 database schema, added as a separate Flyway migration and source DDL, but not yet connected to the Spring application runtime.

The current application is **not yet a tenant-based WMS V1 backend**. The presence of the WMS DDL and migration does not mean the application is using that schema.

The recommended direction is a deliberate modular-monolith refactor. Preserve useful engineering patterns and evidence, but do not carry forward legacy entities, repositories, services, controllers, or authorization assumptions as the WMS V1 domain model.

## 2. Repository and build baseline

- Java 21.
- Spring Boot 3.5.13.
- Spring Data JPA / Hibernate.
- Spring Security.
- JJWT 0.11.5.
- MySQL Connector/J.
- Flyway Core and Flyway MySQL.
- Maven Wrapper.
- JUnit 5, Spring Boot Test, Spring Security Test.
- Testcontainers 1.21.3 for MySQL 8.4 integration tests.
- H2 for legacy application-context/repository tests.
- Dockerfile and Docker Compose are present.
- GitHub Actions CI runs `./mvnw clean verify` on GitHub-hosted Ubuntu with Java 21.

The merged WMS schema PR passed the main-branch CI run recorded after merge. The migration integration test uses a disposable MySQL 8.4 container; it does not connect to a developer database.

## 3. Current source structure

The Java code is currently organized by technical layer at the root:

- `config`
- `controller`
- `entity`
- `exception`
- `filter`
- `io/request`
- `io/response`
- `repository`
- `service`
- `service/impl`
- `util`

This is serviceable for a small CRUD application, but it spreads each business capability across many global packages. It will become harder to evolve as receiving, putaway, inventory, lot/serial tracking, replenishment, picking, packing, shipping, and stock counting are implemented.

### Recommended direction

Use a package-by-feature modular monolith. Each business module owns its API, application use cases, domain model, and persistence adapters. Keep cross-cutting concerns in a small shared area.

Proposed target shape (to be implemented incrementally, not generated wholesale):

```text
com.zirocraft.zirologistics
├── bootstrap
├── shared
│   ├── api
│   ├── security
│   ├── tenancy
│   ├── persistence
│   └── validation
└── modules
    ├── identity
    ├── tenancy
    ├── authorization
    ├── warehouse
    ├── catalog
    ├── inventory
    ├── inbound
    ├── putaway
    ├── replenishment
    ├── picking
    ├── packing
    ├── outbound
    └── stockcount
```

Inside a module, use only the subpackages that add value, such as `api`, `application`, `domain`, and `infrastructure.persistence`. Avoid empty boilerplate packages and avoid creating JPA entities for every table before the corresponding workflow is understood.

## 4. Runtime and migration separation

### Verified repository configuration

- Default Flyway location is the legacy `classpath:db/migration`.
- The legacy migration creates six legacy tables.
- WMS V1 migration is isolated under `classpath:db/wms-migration`.
- `spring.flyway.enabled` is opt-in through `FLYWAY_ENABLED` and defaults to false.
- There is no dedicated WMS Spring profile or WMS runtime persistence configuration yet.
- The current application component scan includes the legacy entities and repositories.
- The current test profile uses H2 and Hibernate `create-drop` for the legacy entity model.

### Safety rule

Do not point the current legacy application at `zirowms_v1`. Do not enable the WMS migration through the legacy Flyway configuration. Do not modify `zirowms_v1` or the legacy `zirologistics_db` during implementation work.

The first runtime implementation task must establish an explicit WMS runtime boundary: dedicated configuration, migration location, entity/repository scanning, and profile behavior. Tests must use disposable MySQL 8.4 Testcontainers databases.

## 5. Schema-to-code incompatibilities

The frozen WMS V1 schema is the authority. Several legacy mappings are structurally incompatible:

| Legacy model | WMS V1 model | Consequence |
|---|---|---|
| `UserEntity.role` | `users`, `tenant_memberships`, `roles`, `permissions`, `membership_roles`, `role_permissions` | Identity, tenant membership, and authorization must be redesigned. |
| `WarehouseEntity.code/name` | Tenant-owned `warehouses.warehouse_code/warehouse_name` | Warehouse identifiers and uniqueness are tenant-scoped. |
| `BinEntity` / `bins` | Hierarchical `warehouse_locations` | Location hierarchy replaces the flat bin model. |
| `ItemEntity.baseUom` text | `units_of_measure`, `item_uom_conversions`, `items.base_uom_id` | UOM references and conversion rules must be modeled. |
| `InventoryStockEntity.quantity` DECIMAL(38,2) | `inventory_balances.on_hand_qty/reserved_qty/available_qty` DECIMAL(19,4) | Quantity, reservation, and balance semantics change. |
| `InventoryLedgerEntity.user_id` | `inventory_ledger.actor_membership_id` | Ledger actor must be an authorized tenant membership. |
| Legacy global request ID | `inventory_ledger(tenant_id, request_id, entry_no)` | Idempotency is tenant-scoped and one request can produce multiple entries. |

WMS V1 includes 63 tables, 165 foreign keys, 131 CHECK constraints, and the frozen `utf8mb4_0900_ai_ci` collation. The DDL and migration were previously compared structurally; the MySQL 8.4 migration integration test checks table, FK, CHECK, engine, collation, and Flyway-history counts.

## 6. Tenant and authorization model

The actual schema establishes:

- `tenants`: tenant identity and tenant defaults.
- `users`: global user identity; email is globally unique.
- `tenant_memberships`: a user's membership in a tenant; unique by `(tenant_id, user_id)`.
- `roles`: tenant-owned roles.
- `permissions`: global permission catalogue.
- `membership_roles`: tenant-consistent assignment of roles to memberships.
- `role_permissions`: permission assignment to roles.

The application currently has no tenant context, no membership-based principal, and no database-backed permission evaluation. Existing `ROLE_ADMIN` / `ROLE_STAFF` checks are legacy behavior and must not be treated as the WMS V1 authorization model.

### Important schema constraint

The 63-table schema has no dedicated warehouse-to-membership assignment table (for example, `warehouse_memberships`). Current relationships support tenant-level membership and tenant-scoped roles, but do not directly express per-user warehouse assignment.

Until the data model is explicitly extended or a separate supported authorization rule is defined, the backend must not claim that it enforces per-warehouse membership restrictions. The safe implemented boundary available from this schema is tenant scope.

### Recommended authentication flow

1. Authenticate the global user identity.
2. Resolve the user's active tenant memberships.
3. Require an explicit tenant/membership selection when the user belongs to multiple tenants.
4. Issue a tenant-scoped access token/context only after validating that membership.
5. On protected requests, derive tenant and membership context from verified authentication—not from an untrusted request body or query parameter.
6. Enforce tenant predicates in every applicable repository query and validate tenant-consistent relationships before writes.
7. Resolve effective permissions from active membership roles and role permissions; never rely on frontend visibility or client-supplied role names.

Token lifetime, revocation behavior, and whether permissions are loaded on each request or cached must be decided as part of the security design.

## 7. Inventory and operational workflow observations

The legacy services contain useful patterns worth retaining as design references:

- Transactional service boundaries.
- Pessimistic locking for selected stock operations.
- Stable ordering when locking two bins during transfers.
- Idempotency checks after acquiring the relevant lock to avoid stale MySQL REPEATABLE READ snapshots.
- MySQL Testcontainers concurrency tests.

These patterns are not a substitute for WMS V1 workflow design. New implementation must use WMS V1 concepts: warehouse locations, inventory balance buckets, lot/serial identity, movement groups, request IDs and entry numbers, actor memberships, and immutable ledger entries.

The legacy transfer implementation appends `_OUT` and `_IN` to request IDs. That convention must not be carried into WMS V1, where `request_id` is CHAR(36) and the ledger supports multiple entries through `entry_no`.

## 8. Security, API, and maintainability findings

1. The public legacy registration endpoint creates a global user without tenant provisioning or membership creation. It is not a valid WMS onboarding flow.
2. `AuthRequest` still exposes a `role` input even though the service correctly ignores it. The new contract should not expose caller-controlled role assignment.
3. Legacy JWT subject is email and does not establish tenant/membership context. JWT validation and claims need a WMS-specific design.
4. The JWT filter catches parsing errors, but user lookup failures are not handled in the filter itself.
5. Authorization is mostly endpoint-level role checks; there is no database-backed permission enforcement or method-security policy for WMS.
6. CORS origin is hard-coded to localhost:5173.
7. Pagination defaults and maximum page sizes/sortable fields are not centrally constrained.
8. Legacy dashboard counts do not consistently represent tenant-scoped, active records.
9. Legacy inventory list queries have no tenant scope.
10. The legacy `BaseEntity` is not a universal WMS base entity. In particular, ledger rows should not inherit mutable update/audit columns that do not exist in the WMS ledger table.
11. Error handling is centralized, but the response contract is ad hoc rather than a stable versioned API error model.
12. Observability is currently basic application logging; structured request/tenant correlation and audit actor semantics need to be designed for WMS.

## 9. Test assessment

The existing suite has useful legacy regression evidence, including:

- Application context smoke test.
- CORS behavior.
- Public registration role-escalation prevention.
- Inactive account JWT rejection.
- Global exception response behavior.
- Legacy Flyway migration test.
- Legacy inventory repository aggregation.
- Legacy service unit tests.
- MySQL concurrency and idempotency tests.
- WMS V1 migration integration test.

The current tests do **not** prove WMS tenant isolation, membership authorization, permission evaluation, tenant-safe warehouse/location access, WMS inventory posting, lot/serial integrity, or WMS operational workflows. The WMS migration test validates the schema migration, not the application runtime against WMS entities.

The README's recorded local count (29 tests across 15 suites) and older CI evidence should be refreshed after the next actual test run. Do not describe planned WMS tests as verified.

## 10. Documentation and CI cleanup

- README currently documents the legacy package structure and legacy six-table startup path. It must be rewritten when the WMS runtime becomes the default documented application.
- README's CI evidence is dated and explicitly says the latest adjustments were not rerun. Refresh it only from actual workflow evidence.
- CI currently includes `audit/backend-hardening` as a push and pull-request target. Remove this obsolete branch trigger when the branch is retired.
- The WMS baseline document's status line still says migration validation is pending; update it to reflect the successful merged main-branch migration CI evidence.
- The old portfolio docs branch describes the legacy API and data model and should not be merged into the current WMS documentation.

## 11. Branch disposition

The following branches are obsolete for the current implementation direction:

- `audit/backend-hardening`: contains a divergent legacy hardening line. Useful ideas (Docker, CI, locking, and test patterns) are already represented in the current repository or superseded by the frozen WMS schema. Do not merge this branch.
- `docs/portfolio-documentation`: documents the legacy endpoints and layered architecture. Do not merge this branch.

The current `main` branch remains the baseline. Retire the two obsolete branches after confirming the local working tree is clean.

## 12. Recommended implementation sequence

1. Retire obsolete branches and remove their CI branch trigger.
2. Establish the WMS runtime boundary without touching either developer database.
3. Add a schema-aligned WMS persistence model for tenant identity, users, memberships, roles, and permissions.
4. Implement tenant/membership authentication context and authorization, with MySQL integration tests.
5. Implement warehouse and hierarchical warehouse-location access.
6. Implement item catalogue and UOM references/conversions.
7. Implement inventory balances and immutable ledger posting primitives.
8. Implement inbound receiving and putaway.
9. Implement transfers, adjustments, and stock counts.
10. Implement lot/serial tracking, replenishment, picking, packing, and outbound workflows.
11. Add tenant-scoped audit, idempotency, concurrency, and reconciliation evidence for each posting path.
12. Rewrite README and API/architecture/testing documentation against the actual implemented WMS runtime.

## 13. Non-negotiable implementation safeguards

- The frozen WMS V1 DDL remains the schema authority.
- Do not point the legacy runtime at `zirowms_v1`.
- Do not run schema-changing commands against `zirowms_v1` or `zirologistics_db`.
- All migration/runtime integration tests use disposable MySQL 8.4 Testcontainers databases.
- Never trust a tenant ID supplied by the client as authorization.
- Tenant isolation must be enforced server-side and in persistence queries.
- Keep inventory ledger posting transactional and immutable.
- Preserve decimal precision from the actual schema.
- Do not create all 63 entities blindly; implement domain slices and test each one.
- Mark findings as verified only when backed by an executed test or reproducible runtime evidence.
