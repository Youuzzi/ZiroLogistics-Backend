# MySQL Integer Display Width Deprecation Audit

**Status:** Source identified; baseline warnings accepted; forward-migration guard added.  
**Audit date:** 2026-11-16  
**Evidence:** Backend CI run `43856316123`, job `133261368560`, on PR #47 commit `d0b2b981fed580768ffd7afa2558390e34699183`.  
**Database scope:** CI-owned disposable MySQL 8.4 Testcontainers only.

## 1. Executive result

The warning

```text
DB: Integer display width is deprecated and will be removed in a future release.
(SQL State: HY000 - Error Code: 1681)
```

is emitted by MySQL 8.4 while Flyway executes historical V1 migrations containing integer display-width syntax, primarily `TINYINT(1)`.

It is a deprecation warning, not a failed migration. The CI run completed successfully.

The CI job emitted 93 occurrences across its disposable migration runs:

| Migration executed in CI | Occurrences |
|---|---:|
| WMS V1 — `zirowms_v1_identity_test` | 15 |
| WMS V1 — `zirowms_v2_throttle_adapter_test` | 15 |
| WMS V1 — `zirowms_credential_auth_test` | 15 |
| WMS V1 — `zirowms_login_membership_test` | 15 |
| WMS V1 — `zirowms_v1_migration_test` | 15 |
| WMS V1 — `zirowms_v2_throttle_test` | 15 |
| Legacy V1 — `zirologistics_migration_test` | 3 |
| **Total** | **93** |

The warning occurs while migrating to version 1. The WMS V2 throttle migration itself emitted no integer-display-width warning.

## 2. Source locations

The active SQL migration sources contain these declarations:

- `src/main/resources/db/wms-migration/V1__create_tenant_wms_schema.sql`: 15 occurrences of `TINYINT(1)`.
- `src/main/resources/db/migration/V1__initial_schema.sql`: 3 occurrences of `TINYINT(1)`.
- `src/main/resources/db/wms-migration/V2__create_wms_login_throttle_buckets.sql`: no integer display-width declaration.

The WMS V1 source DDL snapshot also reflects the same 15 declarations:

- `docs/database/source/zirowms_v1_schema.sql`

That snapshot is source evidence, not an additional Flyway migration.

## 3. Why we do not edit V1

Both V1 files are historical migrations. WMS V1 is explicitly frozen and its source DDL is authoritative. Editing an already-applied Flyway migration would change its checksum and can cause validation failures in environments where it has already been applied.

The legacy V1 migration is also treated as immutable after deployment. The fact that it is not the WMS baseline does not make rewriting an applied migration safe.

Therefore this audit deliberately does not:
- edit either V1 migration;
- change the frozen WMS source DDL snapshot;
- run ALTER statements against any user-owned database;
- suppress or downgrade MySQL warnings in logging;
- change Boolean/TINYINT mappings without a compatibility test.

## 4. Why a blind TINYINT(1) replacement is not approved

MySQL historically uses `TINYINT(1)` as a common Boolean representation. Although display width is deprecated and does not change integer storage capacity, removing `(1)` can affect how schema metadata is reported and how JDBC drivers expose the column type.

Before changing an existing Boolean column representation, we must test:
- MySQL 8.4 `SHOW CREATE TABLE` and `INFORMATION_SCHEMA` metadata;
- Connector/J JDBC type and precision metadata;
- Hibernate schema validation for the WMS Boolean entity mappings;
- round-trip persistence of true/false values;
- existing constraints, defaults, indexes, and application queries.

Changing to `BIT(1)`, `BOOLEAN`, or plain `TINYINT` is not automatically equivalent at the application/JDBC boundary. No such conversion is justified by this warning alone.

## 5. Forward policy

A migration policy test has been added:

`src/test/java/com/zirocraft/zirologistics/integration/IntegerDisplayWidthMigrationPolicyTest.java`

It scans both Flyway migration locations and rejects integer display-width declarations in migrations with version greater than 1. Historical V1 migrations are intentionally exempt so they remain unchanged.

New migrations should use integer types without display widths, for example `INT`, `BIGINT`, and `TINYINT` where the selected JDBC/Hibernate mapping is appropriate. Boolean columns require explicit mapping and integration-test coverage rather than a mechanical text replacement.

## 6. Remediation decision

**Current decision: preserve V1 and accept the known warning.**

This is the safest choice because:
1. all warnings are attributable to known historical V1 declarations;
2. migrations completed successfully on MySQL 8.4;
3. the current additive V2 migration introduces no such syntax;
4. a cosmetic rewrite of historical migrations is unsafe;
5. altering Boolean storage types needs a separate compatibility assessment.

The new policy test prevents the warning pattern from being reintroduced in future migrations. If a future schema version needs to normalize existing Boolean columns, it must be proposed as a separate additive migration with Testcontainers/JDBC/Hibernate compatibility evidence. Such a migration would not erase the warning emitted when a clean database first applies the historical V1 migration.

## 7. Verification boundaries

- CI logs were inspected for warning count and the immediately preceding Flyway migration.
- SQL source files were scanned for integer display-width declarations.
- No user-owned database was queried or modified.
- No schema migration was added by this audit.
- The forward-policy test must pass in CI before this audit is considered complete.
