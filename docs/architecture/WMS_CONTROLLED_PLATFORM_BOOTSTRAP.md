# WMS Controlled Platform Bootstrap

**Status:** Implemented as a disabled-by-default, one-time, non-web first-install operation. It creates the initial WMS tenant owner; it does not create a global platform operator.
**Execution boundary:** Dedicated short-lived operator process, explicit command mode, out-of-band secret injection, fresh-database checks, and transactional completion marker.

## Purpose

Create the initial tenant and tenant-owner identity in a fresh WMS installation without exposing public registration or a "create first admin" HTTP endpoint. This is a deployment operation, not a tenant-facing API. The platform operator identity plane is separate and must be provisioned through its own future controlled process; WMS tenant bootstrap must not be treated as platform-admin creation.

## Security invariants

1. Bootstrap is disabled by default and is never enabled by the ordinary WMS runtime profile alone.
2. Bootstrap credentials and tenant details are supplied through a one-time deployment secret channel. They must not be committed, placed in application logs, command history, container image layers, or checked-in properties.
3. Bootstrap runs only in explicit non-web command mode with the WMS datasource and WMS Flyway location.
4. The operation is atomic: initial tenant, WMS user, active tenant membership, tenant Owner role, canonical Owner grants, operational system-role templates, and bootstrap completion marker commit together or not at all.
5. A database-backed singleton bootstrap marker is locked with `SELECT ... FOR UPDATE`. Concurrent attempts serialize; exactly one can complete.
6. A completed marker permanently prevents rerunning bootstrap. There is no HTTP reset or force switch. Recovery requires a separately reviewed and audited operator procedure.
7. Bootstrap fails closed if the database is not an expected fresh WMS installation or any identity/tenant uniqueness conflict exists.
8. The initial user's password is encoded using the WMS Argon2id encoder. Raw passwords are never persisted or logged.
9. The initial WMS user is email-verified as part of this trusted out-of-band bootstrap. Normal invited users use the email-verification lifecycle.
10. No legacy database, user table, credentials, or schema is read or modified.
11. No platform operator, platform role, platform permission grant, or platform access token is created by this tenant bootstrap.

## Initial tenant authorization

The bootstrap-created `TENANT_OWNER` role receives the canonical least-privilege template from `WmsTenantPermissionPolicy.tenantOwnerTemplate()`. It includes tenant governance and operational read visibility; it does not grant wildcard access, inventory posting, shipment release/shipping, stock-count approval, adjustment approval/posting, replenishment reversal, or platform permissions.

The same Owner policy is used by repeatable tenant provisioning. Both creation paths also provision the 13 immutable tenant operational role templates through `WmsTenantRoleTemplateProvisioner`. These templates are tenant roles, not platform roles and not mandatory staffing positions. Operational access is assigned separately under tenant RBAC policy.

## Required bootstrap state

The additive WMS migration creates a singleton state table with:
- a fixed singleton primary key constrained to one allowed value;
- status distinguishing `NOT_COMPLETED` from `COMPLETED`;
- completion timestamp;
- created/updated timestamps;
- opaque operation reference.

The migration seeds exactly one `NOT_COMPLETED` row. Runtime locks row ID 1; a missing row is a hard failure.

## Execution contract

The operator-only application command requires:
- `--wms.bootstrap.command-mode=true` to switch Spring to non-web mode;
- `--wms.bootstrap.enabled=true`;
- `WMS_BOOTSTRAP_ENABLED=true`;
- `WMS_BOOTSTRAP_TENANT_CODE`;
- `WMS_BOOTSTRAP_TENANT_LEGAL_NAME`;
- `WMS_BOOTSTRAP_TENANT_DISPLAY_NAME`;
- `WMS_BOOTSTRAP_TIMEZONE`;
- `WMS_BOOTSTRAP_CURRENCY`;
- `WMS_BOOTSTRAP_OPERATOR_EMAIL`;
- `WMS_BOOTSTRAP_OPERATOR_FULL_NAME`;
- `WMS_BOOTSTRAP_OPERATOR_PASSWORD`.

Values must be injected from a secret manager or ephemeral deployment environment, never command-line arguments. The command validates and normalizes inputs before opening the write transaction, performs Argon2id encoding before acquiring database locks, locks bootstrap state, checks freshness, creates all records and grants transactionally, sets completion last, prints only non-sensitive operation/public identifiers, and terminates.

## Fresh-install checks

Bootstrap refuses to run if any user, tenant, tenant membership, tenant role, or completed marker already exists. A failed transaction leaves no partial records and the marker remains `NOT_COMPLETED`.

## Verification

Disposable MySQL 8.4 Testcontainers verify:
- exactly one initial bootstrap marker;
- atomic creation of the WMS tenant owner and all canonical role grants/templates;
- Argon2id password encoding and absence of raw-password persistence;
- invalid configuration and non-empty database refusal;
- transactional rollback and concurrency safety;
- repeated invocation cannot create another tenant or WMS user;
- ordinary HTTP runtime does not trigger bootstrap;
- legacy runtime/database remains unaffected.

## Rollout

1. Provision an empty, dedicated WMS database and verify its target identity through deployment controls.
2. Apply WMS Flyway migrations only.
3. Inject bootstrap values through a secret manager or ephemeral deployment secret.
4. Run the WMS application once in explicit non-web command mode.
5. Verify non-sensitive counts/status and returned public identifiers.
6. Remove secret injection and disable bootstrap.
7. Start ordinary WMS runtime with bootstrap disabled.
8. Preserve the operation result in deployment audit records without storing secrets.

## Explicit non-goals

- No public self-registration or unauthenticated bootstrap endpoint.
- No default credentials or checked-in secrets.
- No platform administrator, global superuser, or platform token creation.
- No billing/payment integration.
- No tenant data deletion or physical purge.
- No changes to frozen historical migrations.
- No access to the local `zirowms_v1` reference database or legacy database.
