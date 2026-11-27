 > **Historical schema baseline:** This document records the initial V1 schema import and its original 63-table structure. The original database name `zirowms_v1` is historical schema provenance, not the current runtime target. The supported runtime now uses the dedicated `zirologistics_wms` database, Java 21, Spring Boot 3.5.16, MySQL 8.4, Flyway 11.20.3, and additive migrations V1–V32. See the [Runtime Boundary](../runtime/wms-runtime-boundary.md), [WMS API v1 Reference](../api/wms-api-v1-reference.md), and [Backend Maintenance Handoff](../project/backend-maintenance-handoff-2026-11-27.md).

# WMS V1 — Actual Schema Implementation Baseline

- **Status:** Schema source frozen; migration passed the main-branch CI gate after PR #11 merge
- **Historical import database:** `zirowms_v1` (not the current runtime database)
- **Ownership model:** tenant-based
- **Target compatibility:** MySQL 8.4 (must pass the dedicated Testcontainers gate)
- **Original application baseline:** Java 21 / Spring Boot 3.5.13 (historical)
- **Migration location:** `classpath:db/wms-migration`

## 1. Source of truth

The implementation source is the complete DDL export at [`docs/database/source/zirowms_v1_schema.sql`](../database/source/zirowms_v1_schema.sql), taken from the user's actual `zirowms_v1` database after the schema audit was closed.

The exported baseline contains:

- 63 unique InnoDB tables;
- 165 foreign-key constraints;
- 131 CHECK constraints;
- 234 text columns standardized to `utf8mb4_0900_ai_ci`;
- all 63 tables use `utf8mb4_0900_ai_ci`;
- BIGINT AUTO_INCREMENT primary keys;
- no application rows or seed data in the export.

The export header reports **MySQL Server 8.0.44**, while the intended deployment/test target is MySQL 8.4. The complete WMS migration was executed by the dedicated integration test on a disposable MySQL 8.4 Testcontainers database, and the PR #11 main-branch CI run passed after merge. This verifies migration compatibility for the tested migration path; it does not by itself verify application runtime mappings or production deployment behavior.

## 2. Tenant model authority

The actual schema is tenant-based. It is not the earlier single-company model described in the superseded DB-14 draft and it must not be implemented using `company_id` ownership assumptions.

The database schema is the authority for table names, column types, nullability, indexes, foreign keys, CHECK constraints, defaults, and collation. Backend authorization must derive tenant scope from authenticated membership and must validate warehouse membership/assignment at every applicable access boundary. A tenant ID supplied by a client is never sufficient authorization.

The old architecture drafts in PR #9 contain assumptions that conflict with this actual database. They are retained as historical design material only and must not be used as current implementation authority.

## 3. Migration isolation and safety

The WMS migration is stored in `src/main/resources/db/wms-migration`, separate from the legacy Flyway location `classpath:db/migration`.

- Legacy `V1__initial_schema.sql` remains unchanged.
- The existing legacy entities and APIs are not yet mapped to the tenant schema.
- Do not enable the WMS migration against the legacy `zirologistics_db`.
- Do not point the legacy application profile at `zirowms_v1` while legacy JPA entities remain active; Hibernate validation will correctly report incompatible mappings.
- WMS V1 is for a genuinely empty, dedicated WMS database only. Existing schema adoption is out of scope.

The migration is generated from the DDL structure, removes dump-only `CREATE DATABASE`, `USE`, `DROP TABLE`, and session-save/restore statements, and preserves the table definitions. Tables are created first without foreign-key clauses; the same 165 foreign-key constraints are attached afterward so alphabetical dump order cannot cause parent-table creation failures. CHECK constraints and indexes remain in their original table definitions.

No sample tenant, user, password, role assignment, or operational data is seeded.

## 4. Verification gate

`WmsV1SchemaMigrationIntegrationTest` uses a disposable MySQL 8.4 Testcontainers database and verifies:

1. Flyway applies V1 successfully and validates its history;
2. exactly 63 base tables are created;
3. exactly 165 foreign keys are created;
4. exactly 131 CHECK constraints are created;
5. all tables use InnoDB;
6. all tables use `utf8mb4_0900_ai_ci`.

The test does not connect to a developer database. The migration PR was merged after its CI run passed. The main-branch CI run after merge also passed. This gate validates migration execution and schema structure only; it does not validate WMS application runtime behavior.

## 5. Historical backend implementation sequence

1. Tenant identity, membership, and authorization context.
2. Warehouse and location access boundaries.
3. Item, UOM, and master-data validation.
4. Inventory balance and immutable inventory ledger/posting model.
5. Inbound receiving and putaway.
6. Transfers and stock counts/adjustments.
7. Reservations, picking, packing, and outbound shipment.
8. Serial/lot tracking and replenishment workflows where represented in the frozen schema.
9. Audit, idempotency, concurrency, and reconciliation tests across all posting paths.

Do not generate all JPA entities blindly from table names. Map domain aggregates deliberately, use explicit tenant-scoped repository queries, and prove isolation and posting invariants with MySQL integration tests.
