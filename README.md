# ZiroLogistics WMS Backend

Tenant-aware Warehouse Management System backend built with Java 21, Spring Boot 3.5.13, MySQL 8.4, and Flyway.

> **Status:** Active development. This repository is not yet declared production-ready. Structural refactoring, QA/security, deployment hardening, and production-readiness gates follow Stage 13.

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
| Framework | Spring Boot 3.5.13 |
| Security | Spring Security, OAuth2 Resource Server, RSA JWT |
| Persistence | Spring Data JPA / Hibernate |
| Database | MySQL 8.4 |
| Schema management | Flyway |
| Build | Maven Wrapper 3.9.14 |
| Tests | JUnit 5, Spring Boot Test, Testcontainers |
| CI | GitHub Actions |

## Runtime boundary

The supported WMS entry point is `com.zirocraft.zirologistics.wms.WmsRuntimeApplication`. It scans only `com.zirocraft.zirologistics.wms` for WMS components, entities, and repositories.

The legacy entry point and technical-layer packages remain isolated transitional source/test material. They are not the WMS domain model and must not be wired to the WMS database. The default executable JAR is WMS; the legacy entry point is available only through the temporary explicit `legacy-runtime` Maven profile. Its final disposition belongs to the dedicated Backend Structural Audit & Refactoring after Stage 13.

WMS migrations are under `classpath:db/wms-migration`. Frozen migrations V1–V26 remain immutable; future schema evolution is additive (V27+). Read [WMS Runtime Boundary](docs/runtime/WMS_RUNTIME_BOUNDARY.md) before configuring a database.

## Requirements

- Java 21
- Docker Desktop / Docker Engine with Docker Compose v2 (recommended local runtime)
- PowerShell on Windows for the commands below
- Maven is provided by the Maven Wrapper

## Run WMS with Docker Compose

The Compose stack creates a dedicated local MySQL 8.4 database named `zirologistics_wms` and a named volume. It does not use the legacy `zirologistics_db` database or frozen source/reference database `zirowms_v1`.

### 1. Prepare environment

```powershell
Copy-Item .env.example .env
notepad .env
```

Replace the example database and root passwords with unique local values. Never commit `.env`.

### 2. Build and start

```powershell
docker compose config
docker compose up --build -d
```

The API is exposed at `http://localhost:8081`. MySQL is available only on the Compose network and is not published to the host.

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

To permanently delete the disposable local Compose database volume and its data:

```powershell
docker compose down -v
```

### Authentication default

The Compose example keeps `WMS_JWT_ENABLED=false` and the public authentication endpoint switch disabled. This is deliberate fail-closed behavior: the WMS HTTP security chain denies requests. Operational APIs require JWT. Anonymous identity routes additionally require `WMS_SECURITY_HTTP_AUTH_ENDPOINTS_ENABLED=true`; this switch does not make operational routes public. Do not enable these settings with placeholder values.

See [Authentication HTTP Contract](docs/architecture/WMS_AUTHENTICATION_HTTP_API_CONTRACT.md), [Controlled Platform Bootstrap](docs/architecture/WMS_CONTROLLED_PLATFORM_BOOTSTRAP.md), and [Local Docker Deployment](docs/deployment/WMS_LOCAL_DOCKER_DEPLOYMENT.md).

## Run WMS directly with Maven

Use a dedicated, empty WMS development database. Do not use a legacy database or the frozen source/reference database.

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
- The legacy migration path `classpath:db/migration` is not the WMS migration path.
- Never point the legacy runtime at the WMS schema.
- Never run WMS migrations against `zirologistics_db` or `zirowms_v1`.
- Migration/runtime integration tests use disposable MySQL 8.4 Testcontainers databases, not developer-owned databases.

## Tests and CI

Run the complete regression suite and package the WMS runtime entry point:

```powershell
.\mvnw.cmd -Pwms-runtime clean verify
```

MySQL integration tests use disposable Testcontainers databases and require Docker. Inspect the Surefire summary and confirm Docker-dependent tests ran rather than being skipped.

### Latest verified CI evidence

- Backend CI run [#645](https://github.com/Youuzzi/ZiroLogistics-Backend/actions/runs/44615501968)
- Main commit: `a160b3ed0a52a9b9d98a8ac49b5e452af9e980ce`
- **352 tests, 0 failures, 0 errors, 2 skipped; BUILD SUCCESS**
- MySQL integration target: MySQL 8.4.13 Testcontainers

This evidence applies to the recorded commit and does not establish production readiness.

## Engineering roadmap

After backend/API Stages 1–13:
1. Backend Structural Audit & Refactoring.
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
