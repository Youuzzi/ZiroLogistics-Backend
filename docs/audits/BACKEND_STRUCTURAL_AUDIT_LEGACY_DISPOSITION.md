# Backend Structural Audit — Legacy Disposition

**Baseline:** `4b655c3be2f2d245b03d260b3dc9ad9673c06823` (PR #158 merged; Main CI #654 successful)  
**Implementation:** Legacy runtime retirement in progress; this PR removes the obsolete application surface while preserving WMS-owned migration tests and all WMS production/tests.

## Verified ownership and disposition

| Area | Baseline | Disposition |
|---|---:|---|
| WMS production Java | 146 files | Retain unchanged |
| Non-WMS production Java | 63 files | Remove: old single-tenant Logistics application, not in WMS component/entity/repository scan |
| WMS-package tests | 73 files after migration-test move | Retain all |
| Old-package tests remaining after WP #158 | 15 files | Remove: tests target retired controllers/services/entities/demo flow/legacy schema; WMS migration contract tests were moved and retained separately |
| Legacy runtime config | `application.properties`, `application-demo.properties` | Remove; WMS uses `application-wms.properties` |
| Legacy test config | `application-test.properties` | Remove with retired legacy test suite |
| Legacy Flyway migration | `db/migration/V1__initial_schema.sql` | Remove from executable classpath; WMS migrations remain under `db/wms-migration` |
| Maven runtime profile | `legacy-runtime` | Remove; WMS is the sole packaged runtime |

## Runtime boundary evidence

- `WmsRuntimeApplication` explicitly scopes component scan, entity scan, and Spring Data repository scan to `com.zirocraft.zirologistics.wms`.
- The default Spring Boot Maven plugin entry point is `com.zirocraft.zirologistics.wms.WmsRuntimeApplication`.
- CI runs `./mvnw --batch-mode --no-transfer-progress -Pwms-runtime clean verify`.
- `application-wms.properties` points Flyway exclusively at `classpath:db/wms-migration`; Flyway remains opt-in.
- All WMS Flyway migration tests and the integer display-width policy test were moved into the WMS test namespace by PR #158. The migration policy test is scoped to the WMS migration directory.
- The retired legacy source/test tree has no place in the WMS runtime or WMS migration history. The build after this deletion is the final compile/test proof that no retained WMS code depends on those classes/resources.

## Safety and scope

- No WMS production classes or WMS tests are deleted by this cleanup.
- No WMS migration V1–V26 is changed.
- No developer-owned database is accessed or modified.
- Legacy single-tenant schema SQL is removed from the application classpath, not run against any database.
- Historical design/audit documentation remains documentation; it is not an executable runtime.

## Validation required

- Full Maven/Testcontainers MySQL 8.4 regression.
- WMS executable Start-Class verification.
- Docker image build and Compose configuration validation.
- Final tree check: no non-WMS production Java package, legacy runtime profile, legacy application properties, or legacy migration path.
