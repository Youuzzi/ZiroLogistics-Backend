# WMS Local Docker Deployment

## Scope

This guide runs the repository's sole supported application runtime: the tenant-based WMS, packaged by the root `Dockerfile` and started by `compose.yml`.

The Compose project has the explicit name `zirologistics-wms`. It creates separate MySQL 8.4 and WMS backend containers, a dedicated `zirologistics_wms` database, a project-scoped named volume, and a WMS Spring Boot artifact packaged with Maven profile `wms-runtime`. MySQL is not published to the host; the API is published on host port 8081.

The explicit project name is a data-safety boundary. It keeps this supported WMS stack separate from older local Compose projects such as `backend-wms`. It must not reuse an older project's MySQL volume, even if the old volume has a similar name. Existing containers and volumes from the old project are not automatically deleted or migrated.

## Prerequisites

- Docker Desktop / Docker Engine;
- Docker Compose v2;
- a clone of this repository.

## Configure

```powershell
Copy-Item .env.example .env
notepad .env
```

Replace both password placeholders. Required values are `WMS_DB_USERNAME`, `WMS_DB_PASSWORD`, `MYSQL_ROOT_PASSWORD`, and explicit `WMS_FLYWAY_ENABLED=true`. The Compose database is an isolated local WMS target; do not repoint it at a developer-owned database.

## Validate and start

```powershell
docker compose --env-file .env config --quiet
docker compose up --build -d
docker compose ps
```

Before starting WMS, the older `backend-wms` backend may still own host port 8081. Stop only that legacy backend container (do not remove it or its MySQL service/volume):

```powershell
docker compose -p backend-wms stop backend
```

Then start the WMS project. Verify that the Compose project is `zirologistics-wms`, the backend is configured for `zirologistics_wms`, and the MySQL volume belongs to this project. Do not attach the old `backend-wms_zirologistics_mysql_data` volume or point Flyway at its legacy `zirologistics` database.

Inspect logs:

```powershell
docker compose logs --tail 200 mysql
docker compose logs --tail 200 backend
```

The backend waits for MySQL health before starting. Hibernate validates the migrated schema. The container runs as a non-root user on Java 21 JRE.

## Authentication behavior

The example keeps JWT and anonymous auth endpoints disabled. This is fail-closed: the WMS HTTP security chain denies requests. Authenticated business API calls are unavailable until valid WMS RSA JWT key material, issuer, audiences, and key ID are provisioned and JWT is enabled.

Operational routes require JWT. The separate `WMS_SECURITY_HTTP_AUTH_ENDPOINTS_ENABLED=true` switch permits only the documented anonymous authentication POST allowlist after JWT is enabled. It does not make operational routes public.

Do not place private keys or other secrets in the committed template. Inject them through the deployment secret-management mechanism. The application currently expects PEM values through WMS JWT environment placeholders; verify the secret-injection representation before enabling JWT.

## Stop and data lifecycle

```powershell
docker compose down
```

Delete the local Compose database volume only when intentionally resetting this disposable environment:

```powershell
docker compose down -v
```

That command permanently deletes only the `zirologistics-wms` project's MySQL volume and data. It does not affect the older `backend-wms` project's volume or databases outside this Compose project.

## Safety boundaries

Never point WMS Flyway at `zirologistics_db`, the old Compose database `zirologistics`, or `zirowms_v1`; reuse an old Compose volume; use legacy `DB_URL` variables for WMS; enable Hibernate schema auto-update; commit `.env` or private secrets; or expose the local MySQL service publicly.

See [WMS Runtime Boundary](../runtime/wms-runtime-boundary.md) and [Authentication HTTP Contract](../architecture/wms-authentication-http-api-contract.md).
