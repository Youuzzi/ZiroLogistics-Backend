# WMS Runtime Boundary

## Purpose

The repository contains a tenant-based WMS runtime and transitional legacy application source. They are separate application generations and must not share an application context or database mapping.

## Supported WMS entry point

- Main class: `com.zirocraft.zirologistics.wms.WmsRuntimeApplication`
- Component scan: `com.zirocraft.zirologistics.wms`
- Entity scan: `com.zirocraft.zirologistics.wms`
- Repository scan: `com.zirocraft.zirologistics.wms`
- Spring profile: `wms`
- Maven packaging profile: `wms-runtime`

The root Dockerfile and Compose stack package and run this WMS entry point. The Maven profile overrides the legacy Spring Boot main class only for the WMS artifact. The default Maven build remains available for transitional legacy source/tests until the dedicated structural audit resolves them.

Only WMS modules placed under this namespace are discovered by the WMS runtime. Legacy controllers, services, configuration, repositories, and entities remain outside the WMS scan and are not imported.

The legacy `ZirologisticsApplication` remains temporarily because legacy tests and source-level dependencies have not yet been fully retired. It is not the Docker/Compose runtime entry point and must never be pointed at the WMS database.

## Database safety

The WMS migration remains isolated at `classpath:db/wms-migration`. It is disabled by default in `application-wms.properties`. Use a dedicated empty WMS development database such as `zirologistics_wms`.

Do not use these databases as a WMS migration target:
- `zirologistics_db`: legacy application database;
- `zirowms_v1`: frozen source/reference database;
- any user-owned or production database not explicitly provisioned for WMS.

The Compose example creates its own isolated MySQL 8.4 service and named volume. It does not publish MySQL to the host. Flyway is enabled there only because the Compose volume is a dedicated WMS runtime target and the committed `.env.example` requires explicit `WMS_FLYWAY_ENABLED=true`.

## Local WMS configuration

```powershell
$env:WMS_DB_URL = "jdbc:mysql://localhost:3306/zirologistics_wms"
$env:WMS_DB_USERNAME = "your_wms_database_user"
$env:WMS_DB_PASSWORD = "your_wms_database_password"
$env:WMS_FLYWAY_ENABLED = "false"
$env:WMS_JWT_ENABLED = "false"
```

After verifying that the connection points to a newly provisioned empty WMS database, explicitly enable Flyway:

```powershell
$env:WMS_FLYWAY_ENABLED = "true"
```

Run with the WMS Maven profile:

```powershell
.\mvnw.cmd -Pwms-runtime spring-boot:run
```

Hibernate uses `validate`; do not enable `ddl-auto=update`. Keep Flyway disabled until the target is verified. The WMS runtime must not use legacy `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `FLYWAY_ENABLED`, or the legacy migration path.

## Authentication safety

- `WMS_JWT_ENABLED=false` is the secure default; the WMS HTTP security chain denies all requests.
- When JWT is enabled, operational routes are registered and protected.
- `WMS_SECURITY_HTTP_AUTH_ENDPOINTS_ENABLED=true` separately enables only the exact anonymous POST allowlist.
- Invitation issuance and existing-account invitation acceptance remain authenticated.
- Do not enable JWT without valid RSA key material, issuer, audiences, key ID, and reviewed deployment configuration.

## Verification

`WmsRuntimeBoundaryIntegrationTest` starts the WMS application context without opening a database connection and asserts representative legacy controllers, services, and repositories are absent. WMS V1 schema migration tests validate against disposable MySQL 8.4 Testcontainers.

## Legacy disposition

Legacy technical-layer packages and the legacy application entry point are not dependencies of the WMS runtime. They remain temporarily because legacy tests/source still compile and are exercised by the current regression suite. The dedicated Backend Structural Audit & Refactoring after Stage 13 must map dependencies and test ownership, migrate only genuinely reusable behavior into WMS-owned modules, replace or retire legacy-only tests, and remove legacy-only code that has no WMS value. Do not move legacy entities or repositories into WMS merely to preserve them.

## Migration rule

Do not modify frozen migrations V1–V26 or the reference DDL. Future schema changes are additive V27+. Do not rename legacy tables or map legacy entities onto WMS tables.
