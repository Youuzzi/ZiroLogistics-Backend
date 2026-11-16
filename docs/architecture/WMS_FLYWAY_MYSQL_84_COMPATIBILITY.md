# WMS Flyway and MySQL 8.4 Compatibility

**Status:** implemented; PR CI #187 passed  
**Baseline:** Spring Boot 3.5.13, Java 21, MySQL 8.4 Testcontainers  
**Scope:** Flyway dependency alignment only

## Finding

The local Testcontainers run recorded in `hasiltest.md` used MySQL 8.4 and emitted:

```text
Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway and support has not been tested. The latest supported version of MySQL is 8.1.
```

The Spring Boot 3.5.13 dependency-management BOM manages Flyway 11.7.2. The project already includes both `flyway-core` and the required `flyway-mysql` module, so the warning is not caused by a missing MySQL module.

Flyway's MySQL 8.4 compatibility warning is resolved in the 11.15.0 line; this change aligns the application to Flyway 11.20.3, the latest 11.x release selected for this compatibility update. Keeping the override within Flyway 11 avoids an unnecessary major-version jump while retaining the Spring Boot 3.5.13 application baseline.

## Change

The Maven property `flyway.version` is explicitly set to `11.20.3`. Spring Boot dependency management uses that property for both:

- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-mysql`

The two Flyway modules must always resolve to the same version.

## Verification

CI must be supplemented by a local dependency-tree check after syncing the change:

```powershell
.\\mvnw.cmd dependency:tree "-Dincludes=org.flywaydb"
```

Expected: both `flyway-core` and `flyway-mysql` resolve to `11.20.3`.

Run the full suite with Docker Desktop running:

```powershell
.\\mvnw.cmd test
```

## Safety boundaries

- No SQL migration files are modified.
- Frozen WMS V1 remains byte-for-byte unchanged.
- WMS V2 remains unchanged.
- Flyway remains disabled by default for the WMS runtime and continues to use only `classpath:db/wms-migration` when explicitly enabled.
- Hibernate remains configured with `ddl-auto=validate`.
- Automated integration tests continue to use disposable Testcontainers databases, never the user's local databases.

## Verification status

GitHub Actions Backend CI run #187 completed successfully on PR head commit `7d39b6e7b2d9ecb5f04eb840d308a6eec2bc335c`.

Observed in the CI job log:

- Flyway connected to disposable MySQL 8.4 databases for WMS identity, throttle adapter, credential authentication, membership projection, WMS V1/V2 migrations, and the legacy initial migration test.
- The previous Flyway warning stating that MySQL 8.4 was newer than the tested range ending at 8.1 did not appear in the CI log.
- WMS V1 and V2 migrations were successfully applied in the integration-test databases.
- Maven reported `Tests run: 101, Failures: 0, Errors: 0, Skipped: 2` and `BUILD SUCCESS`.
- The two skipped tests are the opt-in Argon2id benchmark and concurrency probes; they are intentionally excluded from ordinary CI.

The CI log confirms successful MySQL 8.4 migrations and removal of the prior compatibility warning. The explicit dependency-tree output remains a local verification step; do not mark that separate check complete until it has been run.
