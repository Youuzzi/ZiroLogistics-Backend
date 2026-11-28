# Tenant MFA password reauthentication service

**Status:** Application service implemented behind an opt-in runtime property; HTTP integration and security-lifecycle wiring remain incomplete.

## Scope

This service verifies the current global WMS account password and issues a short-lived, single-use grant for one explicit tenant MFA lifecycle purpose. It does not enroll a TOTP factor, verify a TOTP code, issue a step-up proof, or enable tenant MFA.

The runtime composition is disabled unless both `wms.security.tenant-mfa.enabled=true` and `wms.security.tenant-mfa.reauthentication.enabled=true`. No HTTP controller consumes or exposes the service in this slice.

## Issuance

- Requires the authenticated WMS principal and a non-empty current password.
- Loads the current global user and requires `ACTIVE` status.
- Requires the persisted credential version to match the authenticated principal.
- Verifies the password through the WMS Argon2id `PasswordEncoder`.
- Acquires the user row lock and rechecks user status, credential version, password hash, and optimistic version before persistence.
- Re-resolves the selected membership and verifies exact user, tenant, membership, and credential-version bindings.
- Generates 32 random bytes and returns a canonical unpadded Base64URL handle once.
- Stores only a domain-separated SHA-256 digest of that handle.
- Enforces a five-minute expiry, aligned with V32.

Allowed purposes are the explicit V32 enum values: `MFA_FACTOR_ENROLLMENT`, `MFA_FACTOR_REVOCATION`, and `MFA_FACTOR_RECOVERY`. Callers must map routes/use cases to these server-side values; do not accept arbitrary purpose strings from clients.

## Consumption

Consumption validates the handle's canonical encoding, locks and revalidates the active global user, re-resolves the active selected membership, and invokes the storage port's atomic conditional consume with the exact identity, credential version, and purpose.

Call `consume(...)` inside the same transaction as the protected factor lifecycle mutation. Consumption uses MANDATORY transaction propagation: it rejects calls without an existing transaction and joins the caller's transaction. The caller must wrap grant consumption and the protected factor mutation in that same transaction; a rollback must undo both. Never consume a grant in a controller and pass a boolean to a later service.

## Revocation

`revokeOutstandingForUser(userId)` is transactional and revokes all outstanding grants for the global user. The existing WMS password-change and password-reset completion flows now invoke it inside the same transaction that changes the password and increments the credential version. Other account-security mutation flows must invoke it in their own transaction after locking the user and changing the security state.

## Explicit non-goals

- No public or authenticated HTTP route is added.
- No TOTP enrollment or activation is added.
- No recovery-code or step-up proof is issued.
- No tenant MFA default or runtime enablement is changed.
- No schema migration is added or modified; V1–V32 remain immutable.
- No database credentials, secret material, local database, Docker volume, or running application is changed.

## Verification

Unit tests cover successful issuance, five-minute expiry, digest-only persistence, wrong password rejection, stale credential version, membership mismatch, malformed grant rejection, purpose/identity-bound consumption, and outstanding-grant revocation. MySQL 8.4 storage concurrency and persistence behavior remain covered by the storage adapter integration test from the preceding PR.
