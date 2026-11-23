# ZiroLogistics WMS Backend

Tenant-aware Warehouse Management System backend built with Java 21, Spring Boot 3.5.16, MySQL 8.4, and Flyway.

> **Status:** Active development. This repository is not yet declared production-ready. Backend structural cleanup is merged; QA/security, deployment hardening, and production-readiness gates remain.

## Repositories

| Component | Repository |
|---|---|
| Backend | [ZiroLogistics-Backend](https://github.com/Youuzzi/ZiroLogistics-Backend) |
| Frontend | [ZiroLogistics-Frontend](https://github.com/Youuzzi/ZiroLogistics-Frontend) |

## WMS capabilities

- Global identity, tenant membership, tenant-scoped roles, and persisted permissions.
- Warehouse and hierarchical location master data.
- Item catalogue, UOM, and inventory balances.
- Inbound receiving, putaway, replenishment, picking, packing, and shipping.
- Inventory adjustments, physical stock counts, and inter-warehouse transfers.
- Immutable inventory posting, request idempotency, concurrency controls, and operational reporting.
- Versioned HTTP API under `/api/wms/v1`.

See [WMS v1 API Reference](docs/api/WMS_V1_API_REFERENCE_STAGE_13.md). Route existence alone does not establish production readiness.

## Technology

| Area | Technology |
|---|---|
| Language/runtime | Java 21 |
| Framework | Spring Boot 3.5.16 |
| Security | Spring Security, OAuth2 Resource Server, RSA JWT |
| Persistence | Spring Data JPA / Hibernate |
| Database | MySQL 8.4 |
| Schema management | Flyway |
| Build | Maven Wrapper 3.9.14 |
| Tests | JUnit 5, Spring Boot Test, Testcontainers |
| CI | GitHub Actions |

## Runtime boundary

**WMS is the sole supported application runtime.**

- Entry point: `com.zirocraft.zirologistics.wms.WmsRuntimeApplication`.
- Component, entity, and repository scans are scoped to `com.zirocraft.zirologistics.wms`.
- The default executable JAR, Dockerfile, Compose stack, and CI package the WMS runtime.
- The old single-tenant Logistics application entry point, source tree, legacy-only tests, profiles, and migration path have been retired.
- Historical source-schema material under `docs/database/source` is reference-only; it is not an executable migration.

WMS migrations are under `classpath:db/wms-migration`. Frozen migrations V1–V26 remain immutable; future schema evolution is additive (V27+). Read [WMS Runtime Boundary](docs/runtime/WMS_RUNTIME_BOUNDARY.md) before configuring a database.

## Requirements

- Java 21
- Docker Desktop / Docker Engine with Docker Compose v2 (recommended local runtime)
- PowerShell on Windows for the commands below
- Maven is provided by the Maven Wrapper

## Run WMS with Docker Compose

The Compose stack uses the explicit project name `zirologistics-wms` and creates a dedicated local MySQL 8.4 database named `zirologistics_wms` in its own named volume. This project boundary intentionally isolates the supported WMS runtime from older Compose projects and their containers/volumes (including a previous `backend-wms` project). It does not adopt, rename, mount, or migrate data from those volumes, the historical `zirologistics` database, `zirologistics_db`, or frozen source/reference database `zirowms_v1`. Existing containers and volumes from another project are left untouched.

### 1. Prepare environment

```powershell
Copy-Item .env.example .env
notepad .env
```

Replace the example database and root passwords with unique local values. Never commit `.env`.

### 2. Build and start

```powershell
docker compose config --quiet
docker compose up --build -d
```

The API is exposed at `http://localhost:8081`. MySQL is available only on the WMS Compose network and is not published to the host. The explicit project name creates separate WMS containers, network, and volume; it prevents Compose from adopting/recreating containers belonging to an older project named `backend-wms`. If that older backend still occupies port 8081, stop only its backend service before starting WMS: `docker compose -p backend-wms stop backend`. This leaves its MySQL container and data volume intact.

### 3. Inspect services

```powershell
docker compose ps
docker compose logs --tail 150 mysql
docker compose logs --tail 150 backend
```

### 4. Stop

```powershell
docker compose down
```

To permanently delete the disposable WMS Compose database volume and its data:

```powershell
docker compose down -v
```

This deletes only the `zirologistics-wms` project volume. Do not use `-v` if you need to preserve local WMS data. It does not delete volumes belonging to the older `backend-wms` project.

### CORS configuration

`WMS_CORS_ALLOWED_ORIGINS` is a comma-separated list of exact browser origins. An empty value denies browser origins by default. The Compose example allows only `http://localhost:5173`; production deployments must configure only trusted frontend origins. CORS headers do not bypass WMS authentication or authorization.

### Authentication default

The Compose example keeps `WMS_JWT_ENABLED=false` and the public authentication endpoint switch disabled. This is deliberate fail-closed behavior: the WMS HTTP security chain denies requests. Operational APIs require JWT. Anonymous identity routes additionally require `WMS_SECURITY_HTTP_AUTH_ENDPOINTS_ENABLED=true`; this switch does not make operational routes public. Do not enable these settings with placeholder values.

See [Authentication HTTP Contract](docs/architecture/WMS_AUTHENTICATION_HTTP_API_CONTRACT.md), [Controlled Platform Bootstrap](docs/architecture/WMS_CONTROLLED_PLATFORM_BOOTSTRAP.md), and [Local Docker Deployment](docs/deployment/WMS_LOCAL_DOCKER_DEPLOYMENT.md).

## Run WMS directly with Maven

Use a dedicated, empty WMS development database. Do not use a historical or frozen reference database.

```powershell
$env:WMS_DB_URL = "jdbc:mysql://localhost:3306/zirologistics_wms"
$env:WMS_DB_USERNAME = "your_wms_database_user"
$env:WMS_DB_PASSWORD = "your_wms_database_password"
$env:WMS_FLYWAY_ENABLED = "false"
$env:WMS_JWT_ENABLED = "false"

.\mvnw.cmd -Pwms-runtime spring-boot:run
```

For a newly provisioned empty WMS database only, verify the connection target and explicitly enable Flyway:

```powershell
$env:WMS_FLYWAY_ENABLED = "true"
.\mvnw.cmd -Pwms-runtime spring-boot:run
```

Hibernate uses schema validation. Do not enable Hibernate schema auto-update. Flyway defaults to false outside the dedicated Compose example.

## Database and migration safety

- WMS connection variables: `WMS_DB_URL`, `WMS_DB_USERNAME`, `WMS_DB_PASSWORD`.
- Flyway is controlled independently by `WMS_FLYWAY_ENABLED` and defaults to false.
- WMS migration location: `src/main/resources/db/wms-migration`.
- Historical source/reference DDL under `docs/database/source` is not a migration and must never be executed blindly.
- Never point WMS Flyway at `zirologistics_db` or `zirowms_v1`.
- Migration/runtime integration tests use disposable MySQL 8.4 Testcontainers databases, not developer-owned databases.

## Tests and CI

Run the complete regression suite and package the WMS runtime entry point:

```powershell
.\mvnw.cmd -Pwms-runtime clean verify
```

MySQL integration tests use disposable Testcontainers databases and require Docker. Inspect the Surefire summary and confirm Docker-dependent tests ran rather than being skipped.

### Latest verified CI evidence

- Post-cleanup Main CI run [#671](https://github.com/Youuzzi/ZiroLogistics-Backend/actions/runs/44653864200) — **SUCCESS**
- Main commit: `9c03a2ff93593027d3d8ff6ca8e2ffec492e011a`
- Full regression and WMS packaging, Compose validation, WMS entry-point verification, and Docker image build all passed.
- This CI result does not establish production readiness.

## Engineering roadmap

After backend/API Stages 1–13:
1. Backend Structural Audit & Refactoring — complete; legacy cleanup and runtime documentation are reconciled.
2. Quality Assurance & Security Audit.
3. Deployment & Infrastructure.
4. Production Readiness.

Frontend WMS and frontend-backend integration remain gated until backend structural, QA/security, deployment, and production-readiness work is complete.

## Security

- Never commit `.env`, database credentials, JWT private keys, SMTP credentials, encryption keys, or real user data.
- Use synthetic/anonymized data in test evidence.
- Keep WMS JWT disabled until deployment secrets and security policy are configured.
- Review database targets before enabling Flyway.
- Do not expose MySQL publicly for local development.

## Maintainer

**Zirocraft Studio** · [@Youuzzi](https://github.com/Youuzzi)
