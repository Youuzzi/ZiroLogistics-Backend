# ZiroLogistics WMS Backend

Tenant-aware Warehouse Management System (WMS) backend for the ZiroLogistics SaaS platform.

The repository contains the Java/Spring Boot runtime, persistence and schema migrations, security and authorization foundations, automated tests, local Docker Compose environment, and engineering documentation.

> **Development status:** Active development. Backend feature work is temporarily paused while the documentation handoff is finalized and the frontend foundation is prepared. This repository is **not production-ready**. Several security orchestration, authorization-enforcement, tenant-isolation verification, operational, and release-readiness items remain open. A module or endpoint being present does not mean its complete business workflow or production controls are finished.

## Contents

- [Product scope](#product-scope)
- [Implemented backend areas](#implemented-backend-areas)
- [Architecture overview](#architecture-overview)
- [Technology stack](#technology-stack)
- [Repository structure](#repository-structure)
- [Runtime and database boundaries](#runtime-and-database-boundaries)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [Build and verification](#build-and-verification)
- [Security and bootstrap boundaries](#security-and-bootstrap-boundaries)
- [Frontend integration](#frontend-integration)
- [Project status and continuation](#project-status-and-continuation)
- [Documentation map](#documentation-map)

## Product scope

ZiroLogistics WMS is being developed as a multi-tenant warehouse management backend. Its domain covers warehouse master data and the operational flow of inventory through receiving, putaway, internal stock control, replenishment, picking, packing, and shipping.

The backend also contains tenant identity and access-management capabilities, plus early platform control-plane foundations intended to support SaaS-level operator and tenant governance.

The backend exposes a versioned HTTP API under:

```text
/api/wms/v1
```

The API reference is maintained separately and describes its documented contract and known limitations: [WMS API v1 Reference](docs/api/wms-api-v1-reference.md).

## Implemented backend areas

The following areas have source-code modules in the current runtime:

| Area | Package | Scope represented in the codebase |
|---|---|---|
| Warehouse master data | `warehouse` | Warehouse management and warehouse-level data |
| Inventory | `inventory` | Item/master data, inventory queries, balances, reservations, and posting services |
| Inventory adjustment | `adjustment` | Inventory adjustment service and HTTP endpoint |
| Inbound receiving | `inbound` | Receiving workflow services and HTTP endpoint |
| Putaway | `putaway` | Putaway management services and HTTP endpoint |
| Outbound | `outbound` | Shipment management services and HTTP endpoint |
| Picking | `picking` | Picking management services and HTTP endpoint |
| Packing | `packing` | Packing management services and HTTP endpoint |
| Shipping | `shipping` | Shipping management services and HTTP endpoint |
| Inter-warehouse transfer | `transfer` | Transfer services and HTTP endpoint |
| Stock count | `stockcount` | Stock-count management services and HTTP endpoint |
| Replenishment | `replenishment` | Planning, task execution, and tenant business-date support |
| Operational reporting | `reporting` | Operational reporting services and HTTP endpoint |
| Tenant identity and lifecycle | `identity`, `tenancy` | Users, memberships, invitations, identity actions, tenant profile and lifecycle services |
| Tenant authorization | `authorization`, `governance` | Membership access, role management, permission policy, and governance policy resolution |
| Authentication and security | `security` | Tenant JWT authentication, password encoding, throttling, CORS, and MFA foundations |
| Platform control plane | `platform` | Platform operator identity, platform JWT/security, permission evaluation, tenant lifecycle access, and support-grant governance foundations |
| Bootstrap | `bootstrap` | Controlled initial tenant and tenant-owner bootstrap |

These are implementation areas, not a claim that every end-to-end workflow is complete. Check the relevant API and architecture documents before integrating a specific operation.

## Architecture overview

The runtime is organized by WMS business capability under the single Java package root:

```text
com.zirocraft.zirologistics.wms
```

Within feature packages, responsibilities are separated where implemented into application services, HTTP controllers, domain policies, infrastructure adapters, and persistence components. Not every feature has the same internal sub-package layout; the repository tree below reflects the actual package boundaries rather than imposing a uniform template.

At a high level:

1. **HTTP/API boundary** — controllers expose versioned WMS endpoints.
2. **Application layer** — application services coordinate business operations and authorization checks.
3. **Domain and policy layer** — domain types and policy components represent business and access rules where present.
4. **Persistence/infrastructure layer** — Spring Data JPA repositories, MySQL adapters, and Flyway-managed schema support runtime state.
5. **Security boundary** — tenant and platform authentication/security configurations maintain separate trust domains.

This is a conceptual map of the current code organization, not a guarantee that all workflows have completed every layer or enforcement path. See [Backend API Architecture](docs/architecture/backend-api-architecture.md), [Tenant Isolation and Authorization](docs/security/tenant-isolation-and-authorization.md), and [Backend Documentation](docs/README.md).

## Technology stack

| Area | Technology |
|---|---|
| Language/runtime | Java 21 |
| Application framework | Spring Boot 3.5.16 |
| HTTP | Spring Web / embedded Tomcat |
| Security | Spring Security 6.5.x, OAuth2 Resource Server, RSA-signed JWT |
| Persistence | Spring Data JPA / Hibernate |
| Database | MySQL 8.4 |
| Schema migration | Flyway 11.20.3 |
| Build | Maven Wrapper 3.9.14 |
| Unit/integration testing | JUnit 5, Spring Boot Test, Spring Security Test |
| Database integration testing | Testcontainers with MySQL; H2 is also present for selected tests |
| Coverage | JaCoCo |
| Containerization | Docker and Docker Compose |
| CI/security checks | GitHub Actions, dependency vulnerability scanning (OSV), CodeQL, authenticated API DAST (OWASP ZAP) |

Dependency versions and build configuration are defined in [pom.xml](pom.xml). Workflow definitions are under [.github/workflows](.github/workflows).

## Repository structure

The tree below is based on the repository at the current documentation branch head. It shows the actual top-level layout and the real Java feature-package roots. Repetitive individual source and test files are intentionally omitted.

```text
ZiroLogistics-Backend/
├── .docker/
│   └── maven/
│       └── settings.xml
├── .github/
│   └── workflows/
│       ├── ci.yml
│       ├── codeql.yml
│       ├── wms-dast-authenticated.yml
│       ├── wms-dast-baseline.yml
│       └── wms-k6-readonly-baseline.yml
├── .mvn/
│   └── wrapper/
├── docs/
│   ├── api/
│   ├── architecture/
│   ├── audits/
│   ├── database/
│   ├── deployment/
│   ├── project/
│   ├── roadmap/
│   ├── runtime/
│   ├── security/
│   ├── testing/
│   ├── wms/
│   └── README.md
├── qa/
│   ├── test-plans/
│   └── README.md
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/zirocraft/zirologistics/wms/
│   │   │       ├── adjustment/
│   │   │       ├── application/
│   │   │       ├── authorization/
│   │   │       ├── bootstrap/
│   │   │       ├── governance/
│   │   │       ├── identity/
│   │   │       ├── inbound/
│   │   │       ├── inventory/
│   │   │       ├── outbound/
│   │   │       ├── packing/
│   │   │       ├── picking/
│   │   │       ├── platform/
│   │   │       ├── putaway/
│   │   │       ├── replenishment/
│   │   │       ├── reporting/
│   │   │       ├── security/
│   │   │       ├── shipping/
│   │   │       ├── stockcount/
│   │   │       ├── tenancy/
│   │   │       ├── transfer/
│   │   │       ├── warehouse/
│   │   │       └── WmsRuntimeApplication.java
│   │   └── resources/
│   │       ├── db/
│   │       │   └── wms-migration/
│   │       ├── application-wms.properties
│   │       └── banner.txt
│   └── test/
│       └── java/com/zirocraft/zirologistics/wms/
│           ├── authorization/
│           ├── bootstrap/
│           ├── governance/
│           ├── identity/
│           ├── inbound/
│           ├── inventory/
│           ├── migration/
│           ├── outbound/
│           ├── platform/
│           ├── putaway/
│           ├── replenishment/
│           ├── reporting/
│           ├── security/
│           ├── stockcount/
│           ├── tenancy/
│           ├── transfer/
│           └── warehouse/
├── tests/
│   └── performance/
├── .env.example
├── .dockerignore
├── Dockerfile
├── compose.yml
├── mvnw
├── mvnw.cmd
├── pom.xml
├── osv-scanner.toml
└── README.md
```

### Important package boundaries

- `authorization` contains tenant membership access and role/permission policy code; it is distinct from `platform/authorization`, which contains platform permission evaluation.
- `identity` handles user identity actions and related invitation/email flows; `security/authentication` contains tenant credential authentication and HTTP authentication components.
- `tenancy` contains tenant lifecycle/profile and tenant persistence; `platform/tenancy` contains platform-side tenant lifecycle access.
- `security` is the tenant security boundary; `platform/security` holds the separate platform JWT/security boundary.
- Operational feature packages such as `inbound`, `putaway`, `picking`, `packing`, and `shipping` are separate modules rather than one generic warehouse controller package.

## Runtime and database boundaries

Only the WMS runtime is supported by the current build and container setup:

| Concern | Current setting |
|---|---|
| Application entry point | `com.zirocraft.zirologistics.wms.WmsRuntimeApplication` |
| Spring profile | `wms` |
| Maven profile | `wms-runtime` |
| Runtime configuration | `src/main/resources/application-wms.properties` |
| API base path | `/api/wms/v1` |
| Compose project | `zirologistics-wms` |
| Compose file | `compose.yml` |
| Backend host port | `8081` (container port `8080`) |
| Runtime database | `zirologistics_wms` |
| Flyway location | `classpath:db/wms-migration` |
| Current migration set | `V1`–`V32` |

Important database rules:

- Use the dedicated `zirologistics_wms` database for the WMS runtime.
- `zirowms_v1` is historical reference material and `zirologistics_db` is a legacy database; neither is a migration target for this runtime.
- Applied Flyway migrations are immutable. New schema changes must be additive, beginning at the next available version (currently V33).
- Hibernate is configured with `ddl-auto=validate`; do not change it to `update`.
- The runtime configures MySQL sessions and Hibernate JDBC handling for UTC persistence behavior.
- Never point Flyway at an existing, shared, historical, or production database unless it has been explicitly provisioned and reviewed for this WMS runtime.

See [Runtime Boundary](docs/runtime/wms-runtime-boundary.md), [Local Docker Deployment](docs/deployment/wms-local-docker-deployment.md), and [Flyway/MySQL Compatibility](docs/architecture/wms-flyway-mysql-84-compatibility.md).

## Getting started

### Prerequisites

- Java 21 (for running/building outside Docker)
- Docker Desktop or Docker Engine with Docker Compose v2 (for the recommended isolated local stack)
- PowerShell on Windows for the commands below; adapt shell syntax for macOS/Linux
- Git

Maven is provided by the Maven Wrapper; a separate Maven installation is not required.

### Option A — Run the isolated Docker Compose stack

1. Clone the repository and enter its directory:

   ```powershell
   git clone https://github.com/Youuzzi/ZiroLogistics-Backend.git
   cd ZiroLogistics-Backend
   ```

2. Create the local environment file:

   ```powershell
   Copy-Item .env.example .env
   notepad .env
   ```

3. Replace the example database and root passwords in `.env` with unique local values. Do not commit this file.

4. Validate the Compose configuration and start the stack:

   ```powershell
   docker compose config --quiet
   docker compose up --build -d
   docker compose ps
   docker compose logs --tail 150 backend
   ```

5. The backend listens at `http://localhost:8081`. The API base path is `http://localhost:8081/api/wms/v1`.

The Compose stack provisions a dedicated MySQL 8.4 service and named volume for `zirologistics_wms`. MySQL is available to the backend on the internal Compose network and is not published to the host by this Compose file.

To stop the services without deleting database data:

```powershell
docker compose down
```

To delete the Compose volume and all data stored in it (destructive):

```powershell
docker compose down -v
```

Do not use `-v` if you need to preserve your local WMS database.

### Option B — Run with Maven

Use a dedicated, empty WMS development database that is reachable from your host. Verify the database target before enabling Flyway.

Example PowerShell environment:

```powershell
$env:WMS_DB_URL = "jdbc:mysql://localhost:3306/zirologistics_wms"
$env:WMS_DB_USERNAME = "your_wms_database_user"
$env:WMS_DB_PASSWORD = "your_wms_database_password"
$env:WMS_FLYWAY_ENABLED = "false"
$env:WMS_JWT_ENABLED = "false"

.\mvnw.cmd -Pwms-runtime spring-boot:run
```

For a newly provisioned, empty WMS database only, explicitly enable migrations:

```powershell
$env:WMS_FLYWAY_ENABLED = "true"
.\mvnw.cmd -Pwms-runtime spring-boot:run
```

The application reads its runtime settings from `application-wms.properties`. Do not use the Maven command against the historical or legacy databases.

## Configuration

The tracked [.env.example](.env.example) is the starting template for Docker Compose. Its current variables include:

| Variable | Purpose |
|---|---|
| `WMS_DB_USERNAME`, `WMS_DB_PASSWORD` | Dedicated WMS database credentials |
| `MYSQL_ROOT_PASSWORD` | Local Compose MySQL root password |
| `WMS_FLYWAY_ENABLED` | Enables Flyway for the dedicated runtime database |
| `WMS_CORS_ALLOWED_ORIGINS` | Explicit comma-separated browser origins |
| `WMS_JWT_ENABLED` | Enables tenant JWT security when keys and settings are provisioned |
| `WMS_SECURITY_HTTP_AUTH_ENDPOINTS_ENABLED` | Controls exposure of the tenant HTTP authentication endpoints |
| `WMS_JWT_ISSUER`, `WMS_JWT_SELECTION_AUDIENCE`, `WMS_JWT_ACCESS_AUDIENCE`, `WMS_JWT_KEY_ID` | Tenant JWT trust and token identifiers |

Additional configuration is defined in [application-wms.properties](src/main/resources/application-wms.properties), including tenant JWT public/private key material, platform JWT settings, email delivery/SMTP, tenant MFA key-ring settings, and related runtime controls. Sensitive values must be supplied through a secure local or deployment secret mechanism; do not put real secrets in source control.

## Build and verification

Run the WMS regression suite, package verification, and JaCoCo report generation:

```powershell
.\mvnw.cmd -Pwms-runtime clean verify
```

MySQL integration tests use disposable MySQL 8.4 Testcontainers databases and require Docker. Selected tests also use H2. Review the Surefire test reports and JaCoCo output under `target/` after the build.

The repository CI includes:

- Maven build and test validation for backend-relevant changes.
- Compose configuration validation and packaged-runtime entry-point verification.
- Docker image build.
- OSV dependency vulnerability scanning.
- CodeQL static analysis.
- Authenticated and baseline DAST workflows, plus a read-only performance baseline workflow.

A green CI run is useful verification evidence, but it is not a production-readiness certification or proof that every business workflow and tenant boundary has been exhaustively tested.

## Security and bootstrap boundaries

The runtime uses fail-closed defaults. In the example configuration:

- Tenant JWT is disabled until explicitly configured with provisioned key material.
- Platform JWT is disabled until its separate issuer, audience, and key pair are provisioned.
- Tenant MFA is disabled until its dedicated encryption key ring is provisioned.
- CORS accepts no browser origins unless explicit origins are configured.
- Tenant HTTP authentication endpoints are not exposed by default.
- Identity email delivery is disabled unless explicitly configured with SMTP credentials and a public action URL.

A controlled non-web bootstrap command exists for a completely empty WMS database. Its current implementation creates the initial tenant, tenant user, active membership, Tenant Owner role, and canonical tenant role templates. It **does not** create a Platform Administrator or issue platform credentials.

Bootstrap must only be run after verifying the target database and supplying one-time secrets through a secure, ephemeral environment. Never expose bootstrap values in shell history, logs, source control, or frontend configuration.

Important incomplete areas include:

- Tenant MFA factor persistence and reauthentication-grant foundations exist, but end-to-end enrollment, verification, step-up, recovery, and one-time proof orchestration are not complete.
- Platform operator and support-grant governance foundations exist, but initial Platform Administrator provisioning, full platform authentication HTTP surface, and runtime scoped support-access enforcement remain open.
- Tenant-isolation and authorization verification still requires deeper route-to-query, nested/bulk identifier, worker, concurrency, and rollback coverage.
- Role/permission and separation-of-duties controls continue to require implementation and verification in the relevant workflows.

Do not treat these foundations as completed production controls. See [Controlled Platform Bootstrap](docs/architecture/wms-controlled-platform-bootstrap.md), [Tenant MFA Contract](docs/security/tenant-mfa-and-step-up-authentication-contract.md), and [Platform Control-Plane Review](docs/audits/platform-control-plane-review-2026-11-27.md).

## Frontend integration

The frontend is maintained in a separate repository and is being rebuilt independently of the legacy UI.

The intended integration approach is:

- Build the UI and navigation around the documented WMS domain capabilities.
- Use synthetic mock data behind replaceable data-access adapters during early UI/UX development.
- Align real API calls with the versioned API reference and backend contract before connecting live data.
- Treat tenant context, authentication, role permissions, and error handling as backend-enforced contracts; frontend route guards are not a security boundary.
- Do not represent unfinished MFA, platform support access, or other incomplete backend capabilities as operational features.

Mock data and frontend behavior are not evidence of backend authorization or tenant isolation.

## Project status and continuation

The latest security-focused implementation checkpoint on the current `main` code line includes tenant credential-version enforcement, tenant MFA persistence and reauthentication-grant foundations, JWT trust-domain startup guards, tenant-scoped inventory mutation predicates, outbound fulfillment tenant-isolation source review, and cross-signature rejection tests between tenant and platform JWT decoders.

The current checkpoint, evidence boundaries, and resume order are recorded in the [Backend Maintenance Handoff](docs/project/backend-maintenance-handoff-2026-11-27.md). Remaining work is prioritized in the [Backend Improvement Roadmap](docs/roadmap/backend-improvement-roadmap.md).

Do not infer completion from a package existing, an API reference being present, or a historical audit having passed. Use the handoff and roadmap for the current state and next engineering tasks.

## Documentation map

The [documentation index](docs/README.md) is the canonical navigation point. Key entry points:

| Topic | Document |
|---|---|
| API behavior | [WMS API v1 Reference](docs/api/wms-api-v1-reference.md) |
| API request/response conventions | [API Request/Response Contract](docs/api/api-request-response-contract.md) |
| Architecture overview | [Backend API Architecture](docs/architecture/backend-api-architecture.md) |
| Business workflows | [Business Workflows and Reporting](docs/architecture/business-workflows-and-reporting.md) |
| Warehouse operations | [Warehouse Operations Architecture](docs/architecture/wms-warehouse-operations-architecture.md) |
| Inventory control | [Inventory Control Architecture](docs/architecture/inventory-control-architecture.md) |
| Tenant roles and permissions | [Tenant Role Templates and Permission Governance](docs/architecture/tenant-role-templates-and-permission-governance-policy.md) |
| Platform administration | [Platform Administration and Operations](docs/architecture/platform-administration-and-operations.md) |
| Tenant isolation | [Tenant Isolation and Authorization](docs/security/tenant-isolation-and-authorization.md) |
| Tenant MFA | [Tenant MFA and Step-Up Authentication Contract](docs/security/tenant-mfa-and-step-up-authentication-contract.md) |
| Runtime/deployment | [Runtime Boundary](docs/runtime/wms-runtime-boundary.md) and [Local Docker Deployment](docs/deployment/wms-local-docker-deployment.md) |
| Security and QA status | [Security/QA Baseline](docs/audits/security-qa-baseline.md) and [Security/QA Closure Criteria](docs/audits/security-qa-closure-criteria.md) |
| Current handoff | [Backend Maintenance Handoff](docs/project/backend-maintenance-handoff-2026-11-27.md) |
| Remaining work | [Backend Improvement Roadmap](docs/roadmap/backend-improvement-roadmap.md) |

## Maintainer

**Zirocraft Studio** · [@Youuzzi](https://github.com/Youuzzi)
