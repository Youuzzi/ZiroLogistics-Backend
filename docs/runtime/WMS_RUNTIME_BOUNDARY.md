# WMS Runtime Boundary

## Purpose

The repository currently contains a legacy warehouse application and the frozen WMS V1 database migration. The WMS schema is not yet the persistence model of the legacy application. They must not share an application context accidentally.

## Independent entry point

WMS has a dedicated Spring Boot entry point. The bootstrap is guarded by the `wms` profile and activates that profile when launched through its `main` method. This prevents the legacy application's broad root-package component scan from discovering WMS bootstrap configuration:

- Main class: `com.zirocraft.zirologistics.wms.WmsRuntimeApplication`
- Component scan: `com.zirocraft.zirologistics.wms`
- Entity scan: `com.zirocraft.zirologistics.wms`
- Repository scan: `com.zirocraft.zirologistics.wms`

Only WMS modules placed under this namespace are discovered by the WMS runtime. Legacy controllers, services, configuration, repositories, and entities remain under the legacy namespace and are not imported.

The existing `ZirologisticsApplication` remains the legacy entry point during the migration. Its default profile does not activate the WMS bootstrap. Legacy Spring components marked with `@LegacyRuntime` are excluded when the `wms` Spring profile is active, providing an additional guard if scan boundaries are intentionally changed later.

## Database safety

The WMS migration remains isolated at `classpath:db/wms-migration`. It is not enabled by default. Do not point the legacy application at the WMS database: its entity model is incompatible with the frozen WMS schema.

For a local WMS runtime, configure a dedicated disposable/development database explicitly:

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/zirowms_v1"
$env:DB_USERNAME = "your_wms_database_user"
$env:DB_PASSWORD = "your_wms_database_password"
$env:SPRING_FLYWAY_ENABLED = "true"
$env:SPRING_FLYWAY_LOCATIONS = "classpath:db/wms-migration"
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = "validate"
$env:JWT_SECRET_KEY = "use-a-long-random-secret-for-local-development"
```

The example database name is illustrative. Confirm the database host and target before enabling Flyway. Never use the legacy `zirologistics_db` connection for WMS migrations.

Run the dedicated entry point with the Maven wrapper:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.main-class=com.zirocraft.zirologistics.wms.WmsRuntimeApplication"
```

Keep Flyway disabled until the WMS database connection has been verified. The first runtime slices will add WMS entities and repositories under the WMS namespace; this boundary intentionally does not map legacy entities onto WMS tables.

## Verification

`WmsRuntimeBoundaryIntegrationTest` starts the WMS application context without opening a database connection and asserts that representative legacy controllers, services, and repositories are absent. The existing `WmsV1SchemaMigrationIntegrationTest` continues to validate the frozen migration independently against disposable MySQL 8.4 Testcontainers.

## Migration rule

Do not move legacy entities into the WMS namespace or rename legacy tables to make them appear compatible. Implement the WMS domain against the frozen schema in feature slices, with tenant isolation, authorization, lifecycle, idempotency, and concurrency behavior verified by targeted tests.
