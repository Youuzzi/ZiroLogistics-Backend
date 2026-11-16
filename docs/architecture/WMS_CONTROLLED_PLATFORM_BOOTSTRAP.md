# WMS Controlled Platform Bootstrap

Status: implementation contract — bootstrap remains disabled until an operator deliberately enables it.

## Purpose

Create the first trusted WMS platform operator and initial tenant in a fresh WMS installation without exposing a public registration or "create first admin" HTTP endpoint. This is a deployment operation, not a tenant-facing API.

## Security invariants

1. Bootstrap is disabled by default and is never enabled by the ordinary `wms` runtime profile alone.
2. Bootstrap credentials and tenant details are supplied through a one-time deployment secret channel. They must not be committed, placed in application logs, command history, container image layers, or a checked-in properties file.
3. The bootstrap operation runs only in a dedicated, short-lived operator process with the WMS datasource and Flyway location. It must not run in the legacy application.
4. The operation is atomic: platform operator, initial tenant, active membership, initial tenant role, role grants, and bootstrap completion marker commit together or not at all.
5. A database-backed singleton bootstrap marker is locked with `SELECT ... FOR UPDATE`. Concurrent attempts serialize; exactly one can complete.
6. A completed marker permanently prevents re-running the bootstrap. There is no HTTP reset or "force" switch. Recovery requires a separately reviewed, audited operator procedure.
7. Bootstrap must fail closed if the database is not an expected fresh WMS installation, if required migrations are absent, or if any identity/tenant uniqueness conflict exists.
8. The initial user's password is encoded using the WMS Argon2id encoder. Raw passwords are never persisted or logged.
9. The operator identity is email-verified as part of this trusted out-of-band bootstrap. Normal invited users still use the email verification lifecycle.
10. No legacy database, user table, credentials, or schema is read or modified.

## Initial authorization

The bootstrap-created tenant owner role must be explicit and tenant-scoped. Role grants are sourced from the current permission catalogue using a reviewed allowlist, not a wildcard query that silently grants future permissions. Platform-level privileges and tenant permissions are distinct concepts; tenant owner does not imply platform operator access.

Before enabling bootstrap in a production environment, the allowlist and platform operator capability model must be reviewed. The bootstrap must not invent or silently assign future permission codes.

## Required bootstrap state

Use an additive WMS migration to create a singleton state table with:
- a fixed singleton primary key constrained to one allowed value;
- a boolean/status that distinguishes NOT_COMPLETED from COMPLETED;
- completion timestamp;
- created/updated timestamps;
- optional opaque operation reference (not an email, password, or token).

The migration seeds exactly one NOT_COMPLETED row. The runtime must verify the row exists exactly once; missing or duplicate state is a hard failure.

## Execution contract

The implementation should expose an operator-only application command, not a controller:
- command name: `wms-bootstrap`;
- explicit enable flag defaults to false;
- require tenant code, legal/display name, timezone, currency, operator email, operator full name, and password from secret-backed environment/configuration;
- validate and normalize all inputs before opening the write transaction;
- perform expensive Argon2id encoding before acquiring database row locks;
- lock bootstrap state first, then re-check completion and fresh-install invariants;
- create records and the reviewed minimum role grants in one transaction;
- set the completion marker last in the same transaction;
- print only a success/failure operation reference and generated public identifiers, never secrets or email addresses;
- terminate after execution. It must not remain as a long-lived web process.

A repeated invocation after completion returns a generic already-completed result without revealing the original operator identity. Concurrent invocations must yield one success and one already-completed result.

## Fresh-install checks

The command must refuse to bootstrap when any of the following already exist:
- any user;
- any tenant;
- any tenant membership;
- any tenant role;
- a completed bootstrap marker.

This avoids adopting an unknown or partially initialized database as the platform's first installation. A failed transaction must leave no partial records and the marker remains NOT_COMPLETED.

## Verification requirements

Use disposable MySQL 8.4 Testcontainers only. Required tests:
- migration creates exactly one NOT_COMPLETED marker and enforces singleton semantics;
- fresh installation bootstraps the operator, tenant, membership, role and explicit permission allowlist atomically;
- password is Argon2id encoded and no raw password is persisted;
- missing/invalid configuration fails before writes;
- pre-existing identity or tenant data causes fail-closed refusal;
- rollback leaves no partial bootstrap data;
- concurrent bootstrap attempts result in exactly one completion;
- repeated invocation cannot create another operator or tenant;
- ordinary WMS HTTP runtime cannot trigger bootstrap;
- legacy runtime is unaffected.

## Rollout and operational procedure

1. Provision an empty, dedicated WMS database and verify its target identity through deployment controls.
2. Apply only the WMS Flyway migrations using the WMS migration location.
3. Provide the bootstrap values through a secret manager or ephemeral deployment secret injection.
4. Run the dedicated bootstrap command once.
5. Verify only non-sensitive counts/status and the returned public identifiers.
6. Remove the bootstrap secret injection and disable the bootstrap execution path.
7. Start the ordinary WMS runtime with bootstrap disabled.
8. Preserve the operation result in the deployment audit record without storing the password or other secrets.

## Explicit non-goals

- No public self-registration.
- No unauthenticated bootstrap endpoint.
- No default credentials.
- No hard-coded tenant, email, password, or permission wildcard.
- No changes to frozen V1 or V2 migrations.
- No access to the user's local `zirowms_v1` or legacy database.
- No automatic production execution from application startup.
