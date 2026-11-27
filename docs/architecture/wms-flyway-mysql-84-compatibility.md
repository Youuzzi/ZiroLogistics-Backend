# WMS Flyway and MySQL 8.4 Compatibility

**Status:** Current dependency alignment is implemented; WMS migrations V1–V32 are the current runtime checkpoint.
**Current runtime:** Java 21, Spring Boot 3.5.16, Flyway 11.20.3, MySQL 8.4.
**Runtime database:** `zirologistics_wms`; automated migration tests use disposable MySQL 8.4 Testcontainers databases.
**Scope:** Flyway/MySQL compatibility and migration safety, not a production database migration approval.

## 1. Current compatibility position

The WMS runtime uses matching `flyway-core` and `flyway-mysql` modules at version 11.20.3. The Flyway/MySQL 8.4 compatibility warning from the earlier dependency baseline was addressed by aligning both modules.

The current runtime migration location is `classpath:db/wms-migration`. The applied migration history is V1–V32 and is immutable. Any future schema change must be an additive migration beginning at V33; do not edit or regenerate an applied migration.

A separate MySQL 8.4 integer display-width deprecation warning remains in historical V1 DDL. It is distinct from Flyway's former MySQL support-range warning and is not, by itself, a failed migration. See [MySQL Integer Display Width Deprecation Audit](mysql-integer-display-width-deprecation-audit.md).

## 2. Dependency alignment

The Maven property `flyway.version` is explicitly set to `11.20.3` and is used for both:

- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-mysql`

The two modules must always resolve to the same version. Do not independently override one module.

## 3. Runtime and database safety

- The supported application runtime is `WmsRuntimeApplication` under the `wms` Spring profile.
- The dedicated local Compose stack targets `zirologistics_wms`.
- Flyway is disabled by default in the WMS runtime configuration and enabled only by the controlled migration/deployment configuration.
- Hibernate uses schema validation; it must not silently generate or mutate the schema.
- Historical databases `zirowms_v1` and `zirologistics_db` are not migration targets.
- Automated integration tests must use disposable MySQL 8.4 Testcontainers databases only.
- Never test migration behavior against a developer, staging, or production database.

## 4. Verification evidence and limits

The original dependency-alignment change passed its recorded CI verification on PR #187. That historical run proved the then-current V1/V2 migration path and dependency alignment; it is not the complete current V1–V32 evidence set.

Current migration correctness is verified through the repository's versioned MySQL 8.4 Testcontainers migration/integration tests and the Backend CI run on the exact commit under review. Do not reuse the old PR #187 test count as a current test-suite total.

The integer display-width audit records a separate warning emitted by frozen migration DDL. Do not edit V1 merely to silence that warning; any schema modernization requires an explicit additive compatibility decision.

## 5. Related references

- [Runtime Boundary](../runtime/wms-runtime-boundary.md)
- [Local Docker Deployment](../deployment/wms-local-docker-deployment.md)
- [MySQL Integer Display Width Deprecation Audit](mysql-integer-display-width-deprecation-audit.md)
- [WMS V1 Schema Implementation Baseline](wms-v1-schema-implementation-baseline.md)
