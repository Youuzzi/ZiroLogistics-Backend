# Backend Structural Audit — Legacy Disposition

**Baseline:** `d1205f3f4940a792e4839d345790ad7715816fce` (Stage 13 WP11 merged; Main CI #652 successful)  
**Scope:** Backend source, tests, runtime configuration, migrations, Maven dependencies, packaging, and repository support files.  
**Status:** Initial inventory completed; dependency-level disposition and migration work in progress.

## Objective

Make the repository a single-purpose WMS backend. Legacy code is not retained merely because it exists. Code still required by WMS is to be migrated into the WMS package structure with regression protection; unrelated retired application code and configuration are to be removed after references and runtime ownership are verified.

## Initial inventory (verified from repository tree)

| Area | Current inventory | Initial assessment |
|---|---:|---|
| WMS production Java | 146 files | Active WMS source; retain and review structure |
| Non-WMS production Java under `com.zirocraft.zirologistics` | 63 files | Legacy application source; not in WMS component scan |
| WMS-package Java tests | 60 files | Active WMS tests; retain |
| Tests under the old non-WMS package | 28 files | Mixed ownership; classify individually, do not bulk-delete |
| Main resources | `application-wms.properties`, `application.properties`, `application-demo.properties`, legacy and WMS migration locations | WMS config is active; legacy config/migration needs reference audit |
| Test resources | `application-test.properties` | H2 + legacy JWT properties; test ownership and actual loading need verification |

Counts are file counts from the recursive Git tree at the baseline commit, not test execution counts.

## Confirmed runtime boundary

- `WmsRuntimeApplication` is the packaged WMS entry point.
- Its `@SpringBootApplication(scanBasePackages = "com.zirocraft.zirologistics.wms")`, `@EntityScan`, and `@EnableJpaRepositories` are restricted to the WMS package.
- CI runs `./mvnw --batch-mode --no-transfer-progress -Pwms-runtime clean verify`. The profile selects the WMS executable entry point; it does **not** exclude legacy-package tests from Maven's test lifecycle.
- The temporary `legacy-runtime` Maven profile still packages the retired `ZirologisticsApplication`; its removal is a target after the dependency and runtime audit.

## Test ownership findings

The old package's 28 tests are not all legacy tests.

- WMS migration tests V1–V12 currently live under `com.zirocraft.zirologistics.integration`; they test `classpath:db/wms-migration` and must be retained and moved under the WMS test namespace.
- `IntegerDisplayWidthMigrationPolicyTest` checks both legacy and WMS migration locations. It is WMS-relevant and should be narrowed to the WMS migration location after legacy migration retirement, then moved under WMS tests.
- `MySqlInventoryLockingIntegrationTest`, the legacy Flyway schema test, old application/controller/security/service/repository tests are coupled to legacy entities, services, endpoints, and schema. They are candidates for removal only after confirming no WMS behavior or unique invariant is lost. Any WMS-relevant behavior must first have an equivalent WMS test.
- The 60 tests already under the WMS namespace remain in place.

## Configuration and migration findings

- `application-wms.properties` is the WMS runtime configuration and points only to `db/wms-migration`; Flyway is opt-in.
- Root `application.properties` contains legacy `DB_*`, `JWT_SECRET_KEY`, `FLYWAY_ENABLED`, and legacy JPA settings. It is not the WMS runtime configuration.
- `application-demo.properties` and `DemoAccountInitializer` belong to the old demo-account flow.
- `src/main/resources/db/migration/V1__initial_schema.sql` creates the old single-tenant legacy schema. It is separate from the WMS migration history.
- `src/test/resources/application-test.properties` contains H2 and legacy JWT settings. Its use by WMS tests must be checked before deletion.
- WMS migrations V1–V26 are frozen and must not be edited, reordered, renamed, deleted, or checksummed differently during this audit.

## Planned disposition

1. Build a source/reference map for every non-WMS production class, old configuration, legacy migration, Maven dependency, and legacy test.
2. Move WMS-owned migration/policy tests from the old package into a clear WMS test namespace without changing test behavior.
3. Replace or remove old-package tests only after equivalent WMS coverage is verified; remove retired legacy-only tests and source.
4. Remove legacy application entry point, controllers, entities, repositories, services, configuration, DTOs, old application properties, demo profile, legacy migration, and `legacy-runtime` profile when references are proven absent.
5. Remove dependencies and resources only when dependency analysis confirms no remaining WMS use.
6. Keep historical design/audit evidence in documentation only where it remains useful and clearly marked historical; do not keep executable legacy runtime code just for history.
7. Run full regression on disposable Testcontainers MySQL 8.4, verify packaged WMS Start-Class and Docker image, and inspect the final runtime/resource boundary.

## Safety constraints

- No developer-owned database is accessed or modified.
- Do not run legacy migrations against WMS or any existing database.
- Do not change frozen WMS migrations V1–V26.
- Do not reduce WMS test coverage to simplify cleanup.
- Do not remove a test merely because it is outside the WMS package; first establish ownership and equivalent protection.
- Every implementation slice is isolated in a PR with full CI; merge sequentially and verify Main CI after each merge.
