# ZiroLogistics Backend

A warehouse management system (WMS) backend built with Java 21 and Spring Boot. The project focuses on reliable warehouse operations for small and medium-sized businesses, including inventory movements, bin capacity tracking, authentication, and data integrity.

> **Project status:** In active development. Features and verification coverage are evolving; this repository does not claim to be a production-ready or fully audited WMS.

## Repositories

| Component | Repository |
|---|---|
| Backend API | [ZiroLogistics-Backend](https://github.com/Youuzzi/ZiroLogistics-Backend) |
| Frontend | [ZiroLogistics-Frontend](https://github.com/Youuzzi/ZiroLogistics-Frontend) |

## Project scope

ZiroLogistics is intended for businesses that manage their own warehouse inventory, such as distributors, trading businesses, and small or medium-sized manufacturers.

The backend is organized around core warehouse workflows rather than full ERP functionality:

- Warehouse and storage-bin management.
- Item and SKU management.
- Inbound, outbound, and bin-to-bin stock transfers.
- Inventory balances and movement history.
- Bin capacity and occupancy tracking.
- Dashboard summary data.
- Authenticated API access with role-based authorization.

## Key capabilities

### Authentication and access control
- Stateless authentication using JWT.
- Spring Security-based request authorization.
- Role-based access control.
- Public registration assigns a restricted staff role rather than trusting a caller-supplied privileged role.
- Inactive accounts are prevented from authenticating through the JWT authorization flow.

### Warehouse and item management
- Warehouse and bin CRUD operations.
- Item and SKU management.
- Paginated inventory and item retrieval where implemented.
- Bin capacity and current occupancy tracking.
- Soft-delete support in the domain model.

### Inventory operations
- Inbound stock receiving into a specified bin.
- Outbound stock issuing with stock validation.
- Stock transfers between bins in the same warehouse.
- Inventory balances by item and warehouse.
- Inventory ledger entries for stock movements.
- Low-stock alert query support.

### Data integrity and reliability
- Transactional service-layer workflows.
- Pessimistic locking for selected inventory operations.
- Request-id idempotency checks for inventory movement workflows.
- Validation and centralized API exception handling.
- Safe generic responses for unexpected server errors.

### Audit metadata
- JPA auditing metadata for entity creation and modification where configured.
- Inventory ledger records provide a history of stock movements.

## Technology stack

| Area | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.13 |
| Security | Spring Security, JWT (JJWT 0.11.5) |
| Persistence | Spring Data JPA, Hibernate |
| Production database target | MySQL |
| Automated test databases | H2 and MySQL 8.4 (Testcontainers) |
| Build | Maven Wrapper |
| Testing | JUnit 5, Spring Boot Test, Spring Security Test, Testcontainers |

## Architecture

The codebase follows a layered Spring application structure:

```text
src/main/java/com/zirocraft/zirologistics/
├── config/        # Application, security, web, and auditing configuration
├── controller/    # REST API controllers
├── entity/        # JPA entities and persistence model
├── exception/     # API exceptions and global exception handling
├── filter/        # JWT request filter
├── repository/    # Spring Data repositories
├── io/
│   ├── request/   # API request models
│   └── response/  # API response models
├── service/       # Service interfaces
│   └── impl/      # Business logic
└── util/          # Shared utilities, including JWT support
```

The service layer contains business workflows, repositories handle persistence, and controllers expose HTTP endpoints. Security and cross-cutting concerns are configured separately.

## Requirements

- Java 21
- Docker Desktop or another Docker-compatible runtime (required for MySQL Testcontainers integration tests)
- MySQL 8.x for running the application against a local database
- PowerShell examples below assume Windows

Maven is provided through the repository's Maven Wrapper, so a separate Maven installation is not required.

## Run locally

### 1. Create a database

Create an empty MySQL database:

```sql
CREATE DATABASE ziro_logistics;
```

### 2. Configure environment variables

PowerShell:

```powershell
$env:DB_URL="jdbc:mysql://localhost:3306/ziro_logistics"
$env:DB_USERNAME="your_username"
$env:DB_PASSWORD="your_password"
$env:JWT_SECRET_KEY="replace-with-a-secure-secret-at-least-32-characters"
```

The application reads database and JWT configuration from environment variables. Do not commit real credentials, production secrets, or local environment files.

### 3. Start the application

```powershell
.\mvnw.cmd spring-boot:run
```

### Database schema behavior

The default Hibernate setting is `validate`. On startup, Hibernate validates the existing schema rather than creating or modifying tables automatically.

For a disposable local development database only, schema update can be enabled explicitly:

```powershell
$env:JPA_DDL_AUTO="update"
$env:JPA_SHOW_SQL="true"
```

Do not use `update` as a production schema migration strategy. Production schema changes should be managed deliberately and reviewed before deployment.


## Local portfolio demo account

A demo account can be provisioned automatically for a local, isolated database. This avoids manual registration while keeping demo credentials out of source control.

The initializer is available only when the `demo` Spring profile is active and `demo.account.enabled=true`. It creates a least-privilege `ROLE_STAFF` account, hashes the password with the application's configured BCrypt password encoder, and leaves an existing account unchanged.

After completing the normal database setup above, configure a dedicated local demo account in PowerShell:

```powershell
$env:DEMO_ACCOUNT_EMAIL="demo@zirologistics.test"
$env:DEMO_ACCOUNT_PASSWORD="ChooseYourOwnDemoPass123"
$env:SPRING_PROFILES_ACTIVE="demo"
.mvnw.cmd spring-boot:run
```

Use a password that meets the application's minimum rule (at least 8 characters, including one uppercase letter and one number). The example password is a placeholder: choose your own value and do not publish it in this repository. The demo profile does not create warehouses, bins, items, or stock records yet; those will be added through a separate synthetic demo-data setup.

For a normal run without demo provisioning, do not activate the `demo` profile. Never point this profile at a production database.

## Tests

Run the complete test suite:

```powershell
.\mvnw.cmd clean test
```

Run the MySQL concurrency integration tests only:

```powershell
.\mvnw.cmd "-Dtest=MySqlInventoryLockingIntegrationTest" test
```

### Test coverage included

- Authentication and registration security integration tests.
- Inactive-account JWT authorization tests.
- CORS security tests.
- Global exception-handler tests.
- Service and repository tests for inventory workflows.
- MySQL 8.4 integration tests managed by Testcontainers for concurrent outbound requests and duplicate request-id handling.

The MySQL integration test starts its own disposable Testcontainers database. Docker must be running for this test. The test is configured to be disabled when Docker is unavailable; therefore, check the Maven/Surefire report to confirm whether it actually ran or was skipped.

Passing tests demonstrate only the scenarios covered by those tests. They do not establish that every workflow, deployment configuration, or production environment has been verified.

## Current engineering focus

The current development branch includes ongoing backend hardening work, particularly:

- Authentication and authorization safeguards.
- Consistent API error handling.
- Inventory transaction integrity.
- Idempotency and concurrency behavior.
- Repository and integration test coverage.

Some areas still require broader verification, including production database deployment behavior, additional inbound and transfer concurrency scenarios, and MySQL-level verification of low-stock query behavior.

## Roadmap

- Continue backend hardening and close remaining verification gaps.
- Improve database setup and repeatable demo data.
- Document verified API workflows and operational setup.
- Integrate the frontend with the backend API.
- Prepare a safe, sanitized database snapshot and a fictional demo account for portfolio demonstrations.

## Portfolio and security notes

This repository is a development project and should not be treated as a hosted public demo unless a deployment is explicitly provided.

Before publishing a database dump or demo credentials:
- Use synthetic or anonymized data only.
- Remove personal, customer, and business-sensitive information.
- Never include production credentials, JWT secrets, API keys, or real user passwords.
- Keep demo credentials isolated and grant only the minimum required access.

## Maintainer

**Zirocraft Studio**  
GitHub: [@Youuzzi](https://github.com/Youuzzi)
