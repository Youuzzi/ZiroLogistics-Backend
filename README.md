# ZiroLogistics Backend

A warehouse management system (WMS) backend built with Java 21 and Spring Boot 3.5.13. It provides REST APIs for warehouse, storage-bin, item, and inventory workflows, with authentication and transactional stock movement handling.

> **Status:** Active development. This repository is a portfolio and development project; it is not presented as a production-ready or fully audited WMS.

## Repositories

| Component | Repository |
|---|---|
| Backend API | [ZiroLogistics-Backend](https://github.com/Youuzzi/ZiroLogistics-Backend) |
| Frontend | [ZiroLogistics-Frontend](https://github.com/Youuzzi/ZiroLogistics-Frontend) |

## Scope and capabilities

- Warehouse and storage-bin management.
- Item and SKU management.
- Inbound stock receiving and outbound stock issuing.
- Bin-to-bin stock transfers.
- Inventory balances, movement ledger, and low-stock query support.
- Bin capacity and occupancy tracking.
- JWT authentication and role-based authorization.
- Request-id idempotency checks and pessimistic locking in selected inventory workflows.
- Centralized API exception handling and JPA audit metadata.

The feature list describes implemented areas; individual endpoints and edge cases may have different levels of test coverage.

## Technology stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.13 |
| Security | Spring Security, JJWT 0.11.5 |
| Persistence | Spring Data JPA, Hibernate |
| Database | MySQL 8.x |
| Schema migrations | Flyway |
| Build | Maven Wrapper |
| Tests | JUnit 5, Spring Boot Test, Testcontainers |

## Architecture

The backend follows a layered Spring application structure:

```text
src/main/java/com/zirocraft/zirologistics/
├── config/        # Application, security, web, and auditing configuration
├── controller/    # REST API controllers
├── entity/        # JPA entities
├── exception/     # API exceptions and global exception handling
├── filter/        # JWT request filter
├── repository/    # Spring Data repositories
├── io/
│   ├── request/   # API request models
│   └── response/  # API response models
├── service/       # Service interfaces
│   └── impl/      # Business logic
└── util/          # Shared utilities
```

Controllers expose HTTP endpoints, services coordinate business workflows, and repositories handle persistence. Security and other cross-cutting concerns are configured separately.

## Requirements

- Java 21
- MySQL 8.x for running the application locally
- Docker Desktop / Docker Engine for Testcontainers tests and Docker Compose
- PowerShell on Windows for the commands below

Maven is provided by the repository's Maven Wrapper.

## Run locally with Maven

### 1. Create an empty database

Create a new, empty database in MySQL:

```sql
CREATE DATABASE zirologistics;
```

### 2. Configure environment variables

PowerShell:

```powershell
$env:DB_URL="jdbc:mysql://localhost:3306/zirologistics"
$env:DB_USERNAME="your_username"
$env:DB_PASSWORD="your_password"
$env:JWT_SECRET_KEY="replace-with-a-secure-secret-at-least-32-characters"
```

Do not commit real credentials, production secrets, or local environment files.

### 3. Apply the initial schema and start the app

Flyway is disabled by default to prevent accidental migration of an existing database. For a **new, empty database only**, explicitly enable the initial migration:

```powershell
$env:FLYWAY_ENABLED="true"
$env:JPA_DDL_AUTO="validate"
.\mvnw.cmd spring-boot:run
```

Flyway scripts are stored in `src/main/resources/db/migration`. The initial `V1__initial_schema.sql` migration creates the six application tables.

**Existing databases must not be pointed at this initial migration as-is.** An existing installation needs a separately reviewed baseline/adoption plan and schema/data compatibility checks. Back up existing data before any future migration-adoption work.

Hibernate uses `validate` by default. It checks the mapped schema at startup; it is not a schema migration mechanism. Do not use Hibernate `update` as a production migration strategy.

For a disposable local development database only, schema auto-update can be enabled explicitly:

```powershell
$env:JPA_DDL_AUTO="update"
```

## Run with Docker Compose

The Compose configuration starts an isolated MySQL instance and builds/runs the backend. It uses a dedicated named volume and enables Flyway for that new Compose database.

Set a local JWT secret before starting:

```powershell
$env:JWT_SECRET_KEY="replace-with-a-secure-local-secret-at-least-32-characters"
docker compose up --build -d
```

The API is exposed at `http://localhost:8081`; MySQL is exposed on host port `3308` to avoid common local MySQL port `3306` and backend port `8080` conflicts. The Compose database is separate from any existing local database. The fallback database passwords in the Compose file are for local development only; set your own `DB_PASSWORD` and `MYSQL_ROOT_PASSWORD` values for local use, and use managed secrets in hosted environments.

To inspect service status and logs:

```powershell
docker compose ps
docker compose logs --tail 100 backend
```

To stop the services without deleting database data:

```powershell
docker compose down
```

To remove the disposable Compose database volume as well (this permanently deletes its data):

```powershell
docker compose down -v
```

Do not run the volume-removal command if you need to keep the Compose database contents.

### Local Docker verification

The Compose stack was started locally with MySQL 8.4 and the backend container. The observed startup logs confirmed that Flyway validated and applied migration V1, Hibernate initialized successfully with schema validation enabled, and the Spring Boot application started. A host request to `http://localhost:8081/` returned HTTP 403, confirming HTTP connectivity while the root path remained protected by Spring Security; this is not an authenticated API workflow test.

## Demo profile

A local demo account initializer is available only when the `demo` Spring profile is active and `demo.account.enabled=true`. It provisions a least-privilege `ROLE_STAFF` account, hashes the password using the configured BCrypt encoder, and leaves an existing account unchanged.

Configure your own local values; never publish demo credentials:

```powershell
$env:DEMO_ACCOUNT_EMAIL="demo@zirologistics.test"
$env:DEMO_ACCOUNT_PASSWORD="ChooseYourOwnDemoPass123"
$env:SPRING_PROFILES_ACTIVE="demo"
.\mvnw.cmd spring-boot:run
```

Use a password meeting the application's validation rules. Do not activate the demo profile against a production database. The initializer does not create warehouses, bins, items, or stock records.

## Tests

Run the complete test suite:

```powershell
.\mvnw.cmd clean test
```

Run the MySQL inventory locking integration test only:

```powershell
.\mvnw.cmd "-Dtest=MySqlInventoryLockingIntegrationTest" test
```

MySQL integration tests use disposable Testcontainers databases and require Docker to be running. Some tests are configured to disable themselves when Docker is unavailable, so inspect the Surefire reports and confirm that the integration tests actually ran rather than being skipped.

### Recorded verification evidence

- Local Surefire reports: **29 tests across 15 suites; 0 failures, 0 errors, 0 skipped**.
- GitHub Actions Backend CI: **successful** for commit `690584a54732a25ffd0125533139d8df0c06ded0` (before the later Compose host-port/documentation-only commits).
- Local Docker Compose smoke test: MySQL container healthy; Flyway V1 applied; Hibernate schema validation and application startup succeeded; host HTTP request received a Spring Security 403 response.

Test results demonstrate only the scenarios covered by the tests; they do not establish that every workflow or production environment has been verified. CI has not yet been rerun for the latest documentation and Compose adjustments.

## CI

GitHub Actions runs the Maven test suite for pushes and pull requests targeting the repository's main development branches. The workflow uses Java 21 and caches Maven dependencies. MySQL Testcontainers tests run using the Docker service available on the GitHub-hosted Linux runner.

## Current engineering focus

- Backend security and authorization hardening.
- Inventory transaction integrity, idempotency, and concurrency.
- Repeatable database setup and schema migration safety.
- API documentation and frontend integration.
- Additional integration coverage for warehouse and inventory edge cases.

## Security and portfolio notes

This is a development project, not a hosted public demo unless a deployment is explicitly provided.

- Use synthetic or anonymized data in screenshots, database exports, and demonstrations.
- Never commit production credentials, JWT secrets, API keys, or real user passwords.
- Keep demo accounts isolated and grant only the minimum required access.
- Review database migrations carefully before applying them to any database containing valuable data.

## Maintainer

**Zirocraft Studio** · [@Youuzzi](https://github.com/Youuzzi)
