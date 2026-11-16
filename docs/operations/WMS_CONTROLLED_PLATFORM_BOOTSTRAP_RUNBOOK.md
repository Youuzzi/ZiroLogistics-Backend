# WMS Controlled Platform Bootstrap Runbook

## Purpose

Create the first WMS tenant, global administrator identity, active tenant membership, and a narrowly scoped initial owner role on a fresh WMS database.

This is an operator-only, one-time operation. It is not an HTTP endpoint and is not loaded by the normal `wms` runtime profile.

## Safety gates

- Use only a newly provisioned WMS database after the V1-V6 migrations are available.
- The bootstrap service refuses to run if any row already exists in `users` or `tenants`.
- A singleton V6 state row serializes concurrent bootstrap attempts and prevents a second successful bootstrap.
- User, tenant, membership, owner role, permission assignment, and bootstrap completion marker commit in one transaction.
- The initial administrator password is hashed with the WMS Argon2id encoder before the write transaction.
- The bootstrap password must be supplied through a secret environment variable. Never put it in command-line arguments, source control, logs, or this runbook.
- The service does not connect to or mutate the legacy database.
- Do not run this operation against an existing developer database. It is intended for a fresh environment.

## Initial permission scope

The initial `TENANT_OWNER` role receives only the explicitly seeded `WMS_IDENTITY_MEMBERSHIP_INVITE` permission. It does not receive every permission in the database, and invitation acceptance does not grant additional roles automatically.

As the WMS authorization catalog grows, additional role-permission assignments must be introduced through reviewed migrations and authorization workflows.

## PowerShell invocation

Set the WMS database connection values from the deployment secret store and set the initial administrator password in the process environment. Do not paste actual credentials into a terminal transcript.

```powershell
$env:SPRING_PROFILES_ACTIVE = "wms,wms-bootstrap"
$env:WMS_DB_URL = "<fresh-wms-jdbc-url>"
$env:WMS_DB_USERNAME = "<wms-db-username>"
$env:WMS_DB_PASSWORD = "<wms-db-password>"
$env:WMS_BOOTSTRAP_ADMIN_PASSWORD = "<inject-from-secret-store>"

.\mvnw.cmd spring-boot:run `
  "-Dspring-boot.run.profiles=wms,wms-bootstrap" `
  "-Dspring-boot.run.arguments=--wms.bootstrap.enabled=true,--wms.bootstrap.tenant-code=ZIROCRAFT,--wms.bootstrap.tenant-legal-name=Zirocraft,--wms.bootstrap.tenant-display-name=Zirocraft,--wms.bootstrap.admin-email=admin@example.com,--wms.bootstrap.admin-full-name=Initial Administrator"
```

The password is bound from `WMS_BOOTSTRAP_ADMIN_PASSWORD` to `wms.bootstrap.admin-password`; it is deliberately not included in the command arguments.

After the application logs the tenant and administrator public IDs, stop the bootstrap process and remove the bootstrap-only environment variables:

```powershell
Remove-Item Env:SPRING_PROFILES_ACTIVE
Remove-Item Env:WMS_BOOTSTRAP_ADMIN_PASSWORD
```

Do not rerun the bootstrap process after completion. The V6 state row will reject subsequent attempts.

## Validation

CI validates V1-V6 migrations and bootstrap behavior with disposable MySQL 8.4 Testcontainers. No production or developer database is used by automated tests.
