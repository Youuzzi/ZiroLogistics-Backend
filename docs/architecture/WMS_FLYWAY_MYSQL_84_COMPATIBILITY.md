# WMS Flyway and MySQL 8.4 Compatibility

**Status:** implemented; PR CI #186 passed  
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

## Required verification

CI must confirm:

1. The Maven dependency graph resolves both Flyway modules to 11.20.3.
2. Flyway starts against disposable MySQL 8.4 Testcontainers without the previous “latest supported version ... 8.1” warning.
3. Existing Flyway migration tests pass for the legacy migration test and WMS V1/V2 migration suites.
4. WMS schema validation and the full Maven test suite pass.

Useful local inspection command:

```powershell
.\mvnw.cmd dependency:tree "-Dincludes=org.flywaydb"
```

Run the full suite with Docker Desktop running:

```powershell
.\mvnw.cmd test
```

## Safety boundaries

- No SQL migration files are modified.
- Frozen WMS V1 remains byte-for-byte unchanged.
- WMS V2 remains unchanged.
- Flyway remains disabled by default for the WMS runtime and continues to use only `classpath:db/wms-migration` when explicitly enabled.
- Hibernate remains configured with `ddl-auto=validate`.
- Automated integration tests continue to use disposable Testcontainers databases, never the user's local databases.

## Verification status

GitHub Actions Backend CI #186 completed successfully on commit `1f4bad3f94f6a67ffb753f1b34bac91b3dfe756e`.

Observed in the CI job log:

- Flyway connected to disposable MySQL 8.4 databases for WMS identity, throttle adapter, credential authentication, membership projection, WMS V1/V2 migrations, and the legacy initial migration test.
- The previous Flyway warning stating that MySQL 8.4 was newer than the tested range ending at 8.1 did not appear in the CI log.
- Maven reported `Tests run: 101, Failures: 0, Errors: 0, Skipped: 2` and `BUILD SUCCESS`.
- The two skipped tests are the opt-in Argon2id benchmark and concurrency probes; they are intentionally excluded from ordinary CI.

The Maven property explicitly pins both Flyway modules through Spring Boot's managed `flyway.version` property. For local dependency-resolution evidence, run the dependency-tree command above after syncing the merged change and confirm both modules report 11.20.3.
