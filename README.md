# ZiroLogistics WMS Backend

Tenant-aware Warehouse Management System backend for the ZiroLogistics SaaS product.

> **Project status:** Active development. The backend is suitable for continued frontend integration work in a controlled development environment, but it is **not production-ready**. Security and tenant-isolation work has meaningful coverage, while several orchestration, access-enforcement, operational, and release-readiness items remain open.

## Product capabilities

The WMS runtime currently contains backend modules for:

- Tenant identity, membership, authentication, and persisted role/permission management.
- Warehouse and hierarchical location master data.
- Item catalogue, inventory balances, inventory posting, and adjustments.
- Inbound receiving and putaway.
- Outbound shipment, picking, packing, and shipping.
- Inter-warehouse transfer, stock count, and replenishment.
- Operational reporting.
- Platform operator identity, platform authorization primitives, tenant lifecycle governance, and support-grant governance foundations.

The presence of a module or endpoint does not by itself mean that its complete business workflow, UI, or production controls are finished. See the API and architecture documentation for the implemented contracts and known gaps.

## Technology

| Area | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.16 |
| Security | Spring Security, OAuth2 Resource Server, RSA JWT |
| Persistence | Spring Data JPA / Hibernate |
| Database | MySQL 8.4 |
| Schema management | Flyway 11.20.3 |
| Build | Maven Wrapper 3.9.14 |
| Tests | JUnit 5, Spring Boot Test, Testcontainers |
| Coverage | JaCoCo |
| CI/security | GitHub Actions, CodeQL, OSV scanning, authenticated DAST workflow |

## Runtime and repository boundary

The only supported application runtime is WMS:

- Entry point: `com.zirocraft.zirologistics.wms.WmsRuntimeApplication`.
- Spring profile: `wms`.
- Maven profile: `wms-runtime`.
- Production source is organized under `com.zirocraft.zirologistics.wms`.
- The default JAR, Dockerfile, Compose stack, and CI package the WMS runtime.
- Historical database exports are reference material only; they are not executable migrations.

## Database and migrations

- Runtime database: dedicated `zirologistics_wms`.
- Flyway location: `classpath:db/wms-migration`.
- Current repository migration set: **V1–V32**.
- All applied migration files are immutable. Future schema changes must be additive, beginning at V33.
- Hibernate uses schema validation; do not use `ddl-auto=update`.
- Never point WMS Flyway at `zirologistics_db`, `zirowms_v1`, or a developer-owned/production database not explicitly provisioned for WMS.

The runtime connection variables are `WMS_DB_URL`, `WMS_DB_USERNAME`, and `WMS_DB_PASSWORD`. Flyway is controlled by `WMS_FLYWAY_ENABLED` and defaults to false outside the dedicated Compose example.

## Local development with Docker Compose

Requirements:

- Java 21
- Docker Desktop / Docker Engine with Docker Compose
- PowerShell on Windows (commands below)
- Maven is provided by the Maven Wrapper

Prepare the local environment:

```powershell
Copy-Item .env.example .env
notepad .env
```

Replace example credentials with unique local values. Never commit `.env`.

Validate and start the isolated WMS stack:

```powershell
docker compose config --quiet
docker compose up --build -d
docker compose ps
docker compose logs --tail 150 backend
```

The API is exposed at `http://localhost:8081`. MySQL is available only on the Compose network and is not published to the host. The Compose project is explicitly named `zirologistics-wms` and uses its own named volume; it does not adopt or migrate older project volumes or historical databases.

Stop the stack:

```powershell
docker compose down
```

To delete only the disposable WMS Compose volume and its data:

```powershell
docker compose down -v
```

Do not use `-v` if you need to preserve local WMS data.

## Local development with Maven

Use a dedicated, empty WMS development database. Verify the target before enabling Flyway.

```powershell
$env:WMS_DB_URL = "jdbc:mysql://localhost:3306/zirologistics_wms"
$env:WMS_DB_USERNAME = "your_wms_database_user"
$env:WMS_DB_PASSWORD = "your_wms_database_password"
$env:WMS_FLYWAY_ENABLED = "false"
$env:WMS_JWT_ENABLED = "false"

.\\mvnw.cmd -Pwms-runtime spring-boot:run
```

For a newly provisioned empty WMS database only, explicitly enable migrations:

```powershell
$env:WMS_FLYWAY_ENABLED = "true"
.\\mvnw.cmd -Pwms-runtime spring-boot:run
```

## Security defaults and identity bootstrap

The runtime is fail-closed by default:

- `WMS_JWT_ENABLED=false`.
- `WMS_PLATFORM_JWT_ENABLED=false`.
- `WMS_TENANT_MFA_ENABLED=false`.
- CORS allows no browser origins until explicit origins are configured.
- The anonymous authentication endpoint switch is disabled by default.
- JWT and MFA key material must be provisioned through deployment secrets; never commit private keys or encryption keys.

A controlled non-web bootstrap command exists for a completely empty WMS database. Its current implementation creates the initial tenant, tenant user, active membership, Tenant Owner role, and canonical tenant role templates. **It does not create a Platform Administrator or issue platform credentials.** Bootstrap must only be run after verifying the database target and injecting one-time secrets through a secure, ephemeral environment. Do not expose bootstrap values in command-line history, logs, source control, or frontend configuration.

Tenant MFA factor persistence is present, but enrollment/verification/step-up/recovery orchestration is not complete. Platform support-grant governance records exist, but a runtime support-access consumer/enforcement path is not yet implemented. These capabilities must not be represented as complete in the frontend.

See [Controlled Platform Bootstrap](docs/architecture/WMS_CONTROLLED_PLATFORM_BOOTSTRAP.md), [Tenant MFA Contract](docs/security/TENANT_MFA_AND_STEP_UP_AUTHENTICATION_CONTRACT.md), and [Platform Control-Plane Review](docs/audits/platform-control-plane-review-2026-11-27.md).

## Build and tests

Run the full WMS regression suite and package verification:

```powershell
.\\mvnw.cmd -Pwms-runtime clean verify
```

MySQL integration tests use disposable MySQL 8.4 Testcontainers databases and require Docker. Review the Surefire and JaCoCo reports; do not infer test coverage from the number of test files. CI success is necessary but is not a production-readiness certification.

## Current engineering status

The latest security-focused changes merged into `main` include:

- Tenant credential-version enforcement in the authenticated principal (#357).
- Tenant MFA reauthentication grants and dedicated throttling (#354, #355).
- Tenant MFA factor lifecycle persistence (#358).
- JWT trust-domain startup guard (#362).
- Tenant-scoped inventory mutation predicates (#364).
- Outbound fulfillment tenant-isolation source review (#365).
- Real RSA cross-signature rejection tests between tenant and platform JWT decoders (#366).

See [Backend Maintenance Handoff](docs/project/backend-maintenance-handoff-2026-11-27.md) for the exact checkpoint, evidence boundaries, and remaining work. See [Backend Improvement Roadmap](docs/roadmap/backend-improvement-roadmap.md) for prioritized follow-up work.

## Frontend integration direction

The frontend is being rebuilt independently rather than extending the legacy UI. Early UI/UX work may use synthetic mock data behind a replaceable data-access layer. API contracts, authentication, tenant context, and permission behavior must be aligned with this backend before real integration. Mock data is not evidence of backend authorization or tenant isolation.

## Security

- Never commit `.env`, database credentials, JWT private keys, SMTP credentials, TOTP encryption keys, recovery codes, or real user data.
- Use synthetic data in tests and screenshots.
- Do not expose MySQL publicly for local development.
- Do not run bootstrap against historical, shared, or production databases.
- Do not claim production readiness based only on green CI or source review.

## Maintainer

**Zirocraft Studio** · [@Youuzzi](https://github.com/Youuzzi)
