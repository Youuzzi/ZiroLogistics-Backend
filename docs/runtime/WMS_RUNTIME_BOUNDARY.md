# WMS Runtime Boundary

## Purpose

This repository runs one supported application: the tenant-aware WMS. The former single-tenant Logistics runtime has been retired. Historical schema exports and audit records may remain as documentation/reference material, but they are not executable runtime components.

## Supported WMS entry point

- Main class: `com.zirocraft.zirologistics.wms.WmsRuntimeApplication`
- Component scan: `com.zirocraft.zirologistics.wms`
- Entity scan: `com.zirocraft.zirologistics.wms`
- Repository scan: `com.zirocraft.zirologistics.wms`
- Spring profile: `wms`
- Maven packaging profile: `wms-runtime`

The default executable JAR, root Dockerfile, Compose stack, and CI package and run this WMS entry point. There is no supported legacy application entry point or legacy-runtime Maven profile.

## Database safety

The WMS migration location is `classpath:db/wms-migration`. Flyway is disabled by default in `application-wms.properties`. Use a dedicated, newly provisioned WMS database such as `zirologistics_wms`.

Do not use these historical databases as a WMS migration target:
- `zirologistics_db`: legacy application database;
- `zirowms_v1`: frozen source/reference database;
- any user-owned or production database not explicitly provisioned for WMS.

The Compose example creates its own isolated MySQL 8.4 service and named volume. It does not publish MySQL to the host. Flyway is enabled there only for that dedicated Compose WMS database.

The SQL export under `docs/database/source` is historical reference material. It contains destructive/schema-selection statements and must not be executed blindly or treated as a WMS migration.

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

Hibernate uses `validate`; do not enable `ddl-auto=update`. Keep Flyway disabled until the target is verified. The WMS runtime uses WMS-prefixed environment variables and the WMS migration path only.

## Authentication safety

- `WMS_JWT_ENABLED=false` is the secure default; the WMS HTTP security chain denies all requests.
- When JWT is enabled, operational routes are registered and protected.
- `WMS_SECURITY_HTTP_AUTH_ENDPOINTS_ENABLED=true` separately enables only the exact anonymous POST allowlist.
- Invitation issuance and existing-account invitation acceptance remain authenticated.
- Do not enable JWT without valid RSA key material, issuer, audiences, key ID, and reviewed deployment configuration.

## Verification

`WmsRuntimeBoundaryIntegrationTest` starts the WMS application context without opening a database connection and asserts representative retired Logistics controllers, services, and repositories are absent. WMS migration and persistence integration tests use disposable MySQL 8.4 Testcontainers databases.

The structural cleanup is merged in PR #162. The post-merge Main CI run `44653864200` completed successfully, including full regression, WMS packaged entry-point verification, Docker image build, and Compose validation.

## Historical disposition

The dedicated structural audit records the disposition of the former 63 production classes and 15 legacy-package tests in [Backend Structural Audit — Legacy Disposition](../audits/BACKEND_STRUCTURAL_AUDIT_LEGACY_DISPOSITION.md). Legacy-only code, configuration, tests, and executable migration resources were retired rather than moved wholesale into WMS. Relevant behavior was reimplemented using WMS tenant boundaries and domain models where required.

Frozen WMS migrations V1–V26 remain unchanged. Future schema changes are additive V27+; do not rename historical tables or map retired entities onto WMS tables.
