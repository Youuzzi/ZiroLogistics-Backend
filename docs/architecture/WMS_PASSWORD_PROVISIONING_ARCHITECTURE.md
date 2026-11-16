# WMS Password Provisioning Architecture

**Status:** Proposed architecture; no provisioning implementation or endpoint is enabled by this document.  
**Runtime:** WMS-only runtime (`wms` profile).  
**Schema:** Frozen V1 remains unchanged. Any durable invitation or recovery-token storage requires a separately reviewed additive migration.  
**Database safety:** This document is based on repository/schema evidence only. It does not connect to or modify any user database.

## 1. Purpose and current context

The WMS database `zirowms_v1` is a newly created development database. The owner confirmed that the read-only query `SELECT COUNT(*) FROM users` returned zero. This is expected for a new database; it is not an authentication defect.

The read-only password-hash inventory returned zero groups because the `users` table contains no rows. This establishes only the current empty state of that database. It does not establish the provenance or format of future or production password hashes.

This document defines the recommended account-provisioning direction before implementation. It does not enable public registration, create users, or change database schema.

## 2. Verified repository and V1 facts

| Area | Evidence | Status |
|---|---|---|
| Global user storage | V1 `users` contains `public_id`, unique `email`, optional unique `username`, required `password_hash VARCHAR(255)`, `full_name`, status, `email_verified_at`, `last_login_at`, timestamps, and optimistic `version`. | VERIFIED — source migration |
| User status | `ACTIVE`, `PENDING_VERIFICATION`, `SUSPENDED`. | VERIFIED — enum and V1 CHECK |
| Membership status | `INVITED`, `ACTIVE`, `SUSPENDED`, `REMOVED`. | VERIFIED — enum and V1 CHECK |
| Membership uniqueness | One row per `(tenant_id, user_id)`; membership is separate from global user identity. | VERIFIED — V1 unique key |
| Email normalization | WMS strips surrounding whitespace, lowercases with `Locale.ROOT`, rejects null/blank, over-254-character and control-character values. | VERIFIED — code |
| Password encoder | WMS-only Argon2id PasswordEncoder: 16-byte salt, 32-byte output, parallelism 1, 19 MiB memory, 2 iterations. | VERIFIED — code and tests |
| Credential authentication | Existing internal use case verifies hashes, performs dummy work for unknown/malformed users, applies throttle, and issues a tenant-selection token after successful active-user authentication. | VERIFIED — code and tests |
| Password provisioning | No WMS user creation, invitation acceptance, password change, or password reset use case is currently implemented. | VERIFIED — repository audit |
| Login timestamp | `last_login_at` exists in V1 and is mapped by `UserEntity`, but the current credential-authentication service does not update it. | VERIFIED — source inspection |
| Real account hash format | No account rows exist in the inspected new development database. Future/production values remain unknown. | UNVERIFIED |

## 3. Identity model: global user versus tenant membership

WMS identity is global, while tenant access is membership-scoped.

- A **User** is one global identity with one canonical email and one password credential.
- A **TenantMembership** grants that user a relationship with one tenant.
- A user may have memberships in multiple tenants without creating duplicate user identities.
- A membership's status is independent of the global user's status.
- Roles are assigned to a membership through the existing membership-role model; role names or permission lists must never be trusted from an invitation request or client payload.

Consequences:

1. Inviting an existing global user to another tenant must not create a second user or overwrite their password.
2. Suspending one membership must not automatically suspend the global user or their other memberships.
3. Suspending the global user must prevent authentication across all tenants.
4. A tenant invitation must be authorized by a currently active membership with the specific invitation/user-management permission. A tenant ID supplied by a client is not proof of authority.
5. The selected tenant and effective permissions continue to be resolved from current database state through the existing membership-access service.

## 4. Recommended provisioning model

### 4.1 No public self-registration

Do not expose an unauthenticated public endpoint that lets arbitrary callers create a global user, tenant, membership, or privileged role assignment.

The recommended initial product model is invitation-based provisioning:

1. An authorized tenant administrator invites an email address into the administrator's own tenant.
2. The service normalizes the email and checks for an existing global user.
3. If the user already exists, create an invitation for a new tenant membership. Do not change the existing password or global profile as a side effect.
4. If no user exists, keep the invitation pending without creating a usable account. On successful invitation acceptance, create the global user and tenant membership in one database transaction.
5. The invitee proves possession of the invitation secret, chooses a password, and completes acceptance.
6. The new global user becomes `ACTIVE` only after the required email/invitation proof and successful password setup. The membership becomes `ACTIVE` only as part of the same successful acceptance operation.
7. Assign only roles that the inviter is authorized to grant. The default role must be least-privileged and explicitly configured; never silently grant tenant-owner or platform-admin privileges.

The invitation workflow must not disclose whether an email already belongs to a WMS user. Responses and email delivery behavior must avoid account enumeration.

### 4.2 First platform/tenant bootstrap

The first platform operator and first tenant owner cannot be created by an ordinary tenant administrator because no authorized tenant membership exists yet.

Treat first bootstrap as a separate controlled operational procedure:
- require an explicit operator-controlled deployment/bootstrap action;
- require a one-time, high-entropy bootstrap secret or equivalent authenticated operator channel;
- ensure the bootstrap action is disabled after successful completion and cannot be re-opened by an unauthenticated request;
- record an audit event identifying the operator and created public identifiers, without recording credentials or invitation secrets;
- do not add a permanent public "create first admin" endpoint.

Exact bootstrap mechanics remain a separate decision and security review item.

## 5. Password lifecycle requirements

### New password setup
- Accept the password only over TLS through an authenticated/secret-bearing flow.
- Validate required presence and configured length bounds; never silently trim, normalize, or truncate passwords.
- Do not log request bodies, passwords, password hashes, invitation tokens, or reset tokens.
- Encode with the WMS Argon2id PasswordEncoder. Never store plaintext or reversible encryption.
- Benchmark current Argon2id parameters on target deployment hardware and representative concurrency before production exposure.
- Hashing should occur outside long-running database transactions. Revalidate and atomically consume the one-time invitation/action token in the transaction that persists the account/membership state.

### Password change
- Require an authenticated global user and proof of the current password, unless a separately approved recovery flow is used.
- Encode the new password with the WMS encoder.
- Persist with optimistic concurrency/version checks so concurrent changes do not silently overwrite one another.
- Define whether password change invalidates existing access tokens. Current access tokens have bounded lifetime, but V1 has no durable session/revocation store; immediate revocation must not be claimed without an additional design.

### Password reset
- Use a high-entropy, single-use, short-lived reset secret delivered to the verified account channel.
- Persist only a cryptographic digest of the secret, never the raw secret.
- Consume the secret atomically; reject expired, consumed, revoked, or mismatched-purpose tokens.
- Return generic responses that do not reveal whether the email exists.
- A reset changes the password hash but must not activate a suspended user or restore removed/suspended memberships.

### Email verification
- Treat email verification separately from tenant membership activation.
- Update `email_verified_at` only after validating a purpose-bound, single-use proof.
- Do not infer verification merely because an administrator typed an email address.
- Define whether the initial bootstrap operator is verified through its controlled bootstrap procedure.

## 6. Persistence and migration boundary

V1 already provides the necessary core identity fields and must remain frozen:
- `users.password_hash` is required and has capacity `VARCHAR(255)`;
- `users.email_verified_at` and `users.last_login_at` are nullable;
- `users.status` supports `PENDING_VERIFICATION`;
- `tenant_memberships.status` supports `INVITED`;
- tenant membership uniqueness prevents duplicate memberships for the same tenant/user pair.

However, V1 does not provide durable one-time invitation, email-verification, or password-reset token storage.

**Recommendation:** design a separately reviewed additive migration for purpose-bound identity action/invitation records before implementing invitation acceptance or password recovery. The storage must include, at minimum, a token digest, purpose, expiry, consumption/revocation state, and the appropriate user/tenant/inviter relationship. The exact normalized table design, indexes, constraints, retention, and concurrency behavior are intentionally deferred to a dedicated schema PR.

Do not:
- edit or regenerate frozen V1;
- store raw invitation/reset tokens;
- use a process-local map as the source of truth for one-time token consumption;
- add token data to JWT claims as a substitute for durable single-use state;
- write directly to the user's database outside approved migrations and application workflows.

## 7. Transaction and concurrency invariants

- Invitation acceptance must be atomic: token consumption, user creation when needed, membership creation/activation, and authorized role assignment either all commit or all roll back.
- Concurrent acceptance of the same invitation must result in at most one successful commit.
- Unique-email and unique-membership constraints remain the final race-condition guards; application pre-checks alone are insufficient.
- Duplicate-key conflicts must map to safe domain outcomes without exposing internal database details.
- A token cannot be consumed for a different purpose, user, email, or tenant.
- Resending an invitation must revoke or supersede the previous active token according to a documented policy.
- Expired/revoked/consumed action records need bounded retention and cleanup.
- Database or token-storage unavailability must fail closed; no account or membership is activated and no success token is issued.

## 8. Audit and privacy requirements

Record security-relevant events such as invitation created, resent, accepted, revoked, password changed, password reset completed, email verified, and bootstrap completed.

Audit events may contain:
- event type and outcome;
- actor's internal/public identifier according to the audit policy;
- target public identifier when one exists;
- tenant public identifier when applicable;
- timestamp and correlation ID.

Never record:
- plaintext passwords or password hashes;
- raw invitation, verification, or reset secrets;
- bearer tokens or Authorization headers;
- raw authentication request bodies;
- unnecessary full email addresses or source IP addresses.

Use the existing privacy-preserving logging/throttle conventions and define retention/access controls for the eventual audit sink.

## 9. Login metadata

The current credential authentication use case does not update `users.last_login_at`.

Before exposing the login endpoint, add a narrowly scoped success operation that updates the timestamp only after:
1. password verification succeeds;
2. the global user is confirmed `ACTIVE`;
3. the successful throttle reset commits.

The update must not change password hash, email, user status, tenant membership, or role assignments. Use UTC consistently and account for optimistic concurrency. A failure to update non-security-critical login metadata must have an explicitly reviewed policy; it must not accidentally issue tokens before required security operations have committed.

## 10. Implementation sequence

1. Approve this identity/provisioning model and define tenant invitation authority/assignable-role rules.
2. Design the additive invitation/action-token schema and Testcontainers migration tests. Keep V1 unchanged.
3. Implement domain/application ports for invitation creation, resend, revocation, acceptance, password setup, reset, and email verification with unit tests.
4. Implement MySQL persistence with atomic one-time token consumption and concurrency integration tests against disposable MySQL 8.4 Testcontainers.
5. Implement password setup/change/reset using the existing WMS Argon2id encoder; add policy and timing tests.
6. Implement the controlled first-tenant bootstrap as a separate operational/security slice.
7. Add `last_login_at` update semantics and tests.
8. Only after these gates, wire narrowly scoped WMS HTTP endpoints and allow-list them in the WMS SecurityFilterChain.
9. Run full integration/security tests; never point Testcontainers or migration tests at `zirowms_v1` or the legacy database.

## 11. Explicit non-goals of this document

- No user creation or password write is performed.
- No endpoint, Spring bean wiring, or registration UI is added.
- No V1 schema or existing database is modified.
- No V2/V3 migration is created in this architecture-only slice.
- No real password, invitation, email, or reset token is generated.
- No production hash-format compatibility is claimed.
- No Redis, refresh-token, session-revocation, or legacy authentication changes are included.

## 12. Related architecture

- `WMS_AUTHENTICATION_AND_TOKEN_ARCHITECTURE.md`
- `WMS_AUTHENTICATION_READINESS_AUDIT.md`
- `WMS_CREDENTIAL_AUTHENTICATION_ORCHESTRATION.md`
- `WMS_READ_ONLY_PASSWORD_HASH_FORMAT_INVENTORY.md`
- `WMS_LOGIN_THROTTLING_DESIGN.md`

