# WMS Database Migration & Fresh Installation Strategy

- **Document ID:** DB-11
- **Version:** 0.1
- **Status:** Proposed — migration policy
- **Parent:** DB-01 WMS Database Architecture
- **Depends on:** DB-04 Core Database Schema Specification; DB-12 Database Testing Strategy
- **Database target:** MySQL 8.4 LTS
- **Migration tool:** Flyway

## 1. Purpose

This document defines how the new WMS database is created, versioned, tested, and evolved.

The new database is the primary development target for the redesigned WMS. The existing zirologistics_db database remains a separate archive/reference and must not be reset, dropped, silently baselined, or modified during initial schema creation.

## 2. Fresh database principle

The first migration set must create the complete approved baseline schema on a genuinely empty database.

The first installation flow is:

1. Create a new empty database with a distinct name.
2. Configure the application to connect only to that new database.
3. Enable Flyway explicitly for the new database.
4. Apply versioned migrations in order.
5. Validate the resulting schema against the approved JPA model.
6. Insert only required deterministic reference data.
7. Run database integration and smoke tests.
8. Keep the legacy database untouched.

The database name, credentials, host, and Flyway enablement must be supplied through environment-specific configuration or secret management, not committed secrets.

## 3. Migration policy

- Use Flyway versioned migrations for schema changes.
- Use deterministic repeatable migrations only for appropriate reference objects where justified.
- Applied versioned migrations are immutable.
- Never edit a migration that has been applied to a shared or persistent database.
- Correct mistakes with a new forward migration.
- Review destructive changes separately and require backup/recovery planning.
- Prefer expand/contract migrations for changes that must coexist with running application versions.
- Keep migration files small enough to review and test.
- Do not mix large demo datasets with required reference data.
- Record migration purpose, affected objects, compatibility, and rollback/recovery considerations.

## 4. Migration sequence proposal

The exact sequence depends on the approved schema, but the first release should be grouped logically:

- V1: company, user/access, warehouse, location, item/UOM reference foundations.
- V2: inventory status, balance, posting identity, movement lines, and integrity constraints.
- V3: inbound receiving and putaway documents.
- V4: internal transfer, stock count, and adjustment documents.
- V5: outbound order, reservation (if approved), picking, and shipment/dispatch.
- Later migrations: additional indexes, operational improvements, and approved tracking extensions.

This is a sequencing proposal, not authorization to create these migrations before DB-02 through DB-10 decisions are resolved. The final migration plan may consolidate or split versions based on dependency and review.

## 5. Flyway configuration

- Flyway must be enabled intentionally for the new database installation profile.
- Hibernate should validate the schema rather than generate or mutate it.
- Production-like configuration must not use ddl-auto update/create.
- Migration execution identity should have only the database privileges required for schema changes.
- Runtime application identity should have only the privileges required for normal operation, where operationally feasible.
- Flyway schema history must be checked during startup/deployment and included in diagnostics.
- Migration failure must stop deployment rather than allowing an application to run against a partial schema.

The current backend configuration has Flyway disabled by default and Hibernate schema handling configured for validation. The fresh installation process must deliberately override the Flyway flag for the isolated new database.

## 6. Legacy database protection and adoption

The existing zirologistics_db has no known Flyway history table and differs from the V1 migration in at least some schema details.

Therefore:
- do not point the new V1 migration at zirologistics_db;
- do not enable Flyway against the legacy database as an experiment;
- do not treat the legacy database as an empty installation;
- do not create a baseline marker without schema and data verification;
- do not copy old rows into the new database without a separately reviewed mapping and reconciliation plan.

If migration of legacy data is later required, it must be a separate project with backup, source snapshot, mapping rules, validation, dry run, reconciliation, and explicit approval.

## 7. Reference data and seed policy

Separate:
- mandatory reference data (for example, controlled UOM/status/location-type values);
- optional demonstration/sample data;
- user-created business data.

Mandatory reference data must be versioned, deterministic, and safe to apply consistently. Demo data must never be required for production startup. No real customer records, passwords, tokens, or secrets may be committed in seed scripts.

## 8. Recovery and operational controls

For each migration, document:
- affected tables and constraints;
- whether the migration is additive, data-transforming, or destructive;
- expected duration and locking implications;
- backup requirement;
- compatibility with the application release;
- validation queries/tests;
- recovery or forward-fix strategy.

A SQL rollback script is not assumed to be safe for every migration. Recovery may require restoring a verified backup or applying a forward corrective migration.

## 9. Required migration tests

- create a completely empty MySQL 8.4 database;
- apply all Flyway migrations in order;
- validate Flyway schema history and checksums;
- start the application with Hibernate validation;
- verify required constraints, indexes, and reference data;
- run integration tests against the migrated schema;
- verify migration failure prevents successful startup/deployment;
- verify rerunning the application does not reapply versioned migrations;
- verify the legacy database is not touched by the fresh-install process.

## 10. Readiness gate

Migration implementation is ready when:
- the final physical schema is approved;
- database name and isolated connection settings are selected;
- Flyway configuration is explicit for the fresh database;
- migration order and reference data are defined;
- schema validation and integration tests pass on MySQL 8.4;
- legacy database protection is verified;
- backup/recovery expectations are documented.

---

**Public documentation note:** This document describes the proposed fresh-install and migration process. It does not claim that a new database has already been created or that migrations have been applied.