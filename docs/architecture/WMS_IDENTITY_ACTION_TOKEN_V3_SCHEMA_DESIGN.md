# WMS Identity Action Token V3 Schema Design

**Status:** Proposed schema design; not an approved migration.  
**Target migration:** WMS V3 (version number reserved for this proposal; confirm current migration sequence before implementation).  
**Runtime:** WMS-only.  
**V1:** Frozen; no edits.  
**Database safety:** Design is based on repository source files only. No user database was accessed or modified.

## 1. Purpose

Define durable, single-use state for WMS invitation acceptance, email verification, and password reset without storing bearer secrets and without relying on process-local memory.

The new development database `zirowms_v1` was confirmed by its owner to contain zero rows in `users`. This is expected for a newly created database. No real WMS password-hash provenance has been established.

## 2. Existing schema constraints and facts

- WMS V1 has global `users` with unique `email`, `password_hash VARCHAR(255)`, `status`, `email_verified_at`, `last_login_at`, and optimistic `version`.
- WMS V1 has `tenants` and `tenant_memberships`; membership is unique on `(tenant_id, user_id)`.
- User statuses include `ACTIVE`, `PENDING_VERIFICATION`, and `SUSPENDED`.
- Membership statuses include `INVITED`, `ACTIVE`, `SUSPENDED`, and `REMOVED`.
- Existing WMS Argon2id encoder is configured separately; this proposal does not change it.
- V1 must not be edited. The existing V2 login throttle migration is additive and remains unchanged.

## 3. Proposed single table

Propose one table, `wms_identity_action_tokens`, for durable purpose-bound actions.

Rationale:
- One common token lifecycle avoids three slightly different implementations of expiry, one-time consumption, revocation, and cleanup.
- A purpose column prevents using an invitation token as a password-reset or email-verification token.
- Nullable relationships are governed by purpose-specific CHECK constraints.
- Only a SHA-256 digest of a cryptographically random, high-entropy bearer token is persisted. The raw secret is shown to the recipient once and never stored or logged.

### 3.1 Proposed columns

| Column | Proposed type | Rules |
|---|---|---|
| `id` | BIGINT AUTO_INCREMENT | Primary key |
| `token_digest` | BINARY(32) | NOT NULL, unique; SHA-256 digest of 256-bit random secret |
| `purpose` | VARCHAR(32) | NOT NULL; `INVITATION`, `EMAIL_VERIFICATION`, `PASSWORD_RESET` |
| `user_id` | BIGINT | Nullable FK to `users.id`; required for verification/reset; optional for invitation to a not-yet-existing global user |
| `tenant_id` | BIGINT | Nullable FK to `tenants.id`; required only for invitation |
| `invited_by_user_id` | BIGINT | Nullable FK to `users.id`; required only for invitation |
| `target_email` | VARCHAR(254) | Nullable; normalized snapshot of the address the action is bound to |
| `expires_at` | DATETIME(6) | NOT NULL, UTC |
| `consumed_at` | DATETIME(6) | Nullable, UTC; set exactly once on successful atomic consumption |
| `revoked_at` | DATETIME(6) | Nullable, UTC; set when superseded or administratively revoked |
| `created_at` | DATETIME(6) | NOT NULL, UTC |
| `created_by_request_id` | VARCHAR(64) | Optional opaque correlation identifier; no raw IP/email/credential data |

All timestamps use UTC consistently with the WMS migration convention.

### 3.2 Proposed constraints and indexes

- Primary key on `id`.
- Unique key on `token_digest`.
- Foreign keys:
  - `user_id -> users.id`
  - `tenant_id -> tenants.id`
  - `invited_by_user_id -> users.id`
- Purpose CHECK allows only the three declared purpose values.
- Invitation CHECK requires `tenant_id`, `invited_by_user_id`, and `target_email`; `user_id` may be NULL or refer to the existing global user.
- Email-verification CHECK requires `user_id` and `target_email`, and requires `tenant_id` and `invited_by_user_id` to be NULL.
- Password-reset CHECK requires `user_id` and `target_email`, and requires `tenant_id` and `invited_by_user_id` to be NULL.
- CHECK that `consumed_at` and `revoked_at` cannot both be non-NULL.
- CHECK that `expires_at > created_at`.
- Index `(user_id, purpose, created_at)` for per-user action history and revocation.
- Index `(tenant_id, target_email, purpose, created_at)` for tenant invitation lookup/resend serialization.
- Index `(expires_at, consumed_at, revoked_at)` for bounded cleanup.

Do not add a partial/conditional unique index for active invitations; MySQL does not provide the required general partial unique-index semantics. Serialize invitation creation/resend by locking a stable tenant row (or another reviewed lock key) inside a short transaction, then revoke previous outstanding invitation records for the same tenant and normalized target email before inserting the new one. The exact locking strategy must be proven by concurrent Testcontainers tests.

### 3.3 Foreign-key delete behavior

Use restrictive/default FK behavior; do not cascade-delete security evidence when a user or tenant is removed. User/tenant lifecycle should suspend, revoke, or anonymize according to a separately approved retention policy. Physical deletion and privacy erasure need a dedicated policy that reconciles legal deletion obligations with security audit retention.

## 4. Purpose semantics

### INVITATION
- `tenant_id` identifies the one tenant being invited into.
- `invited_by_user_id` identifies the global user who initiated the invitation. Authorization must be checked at creation time against an active membership and a specific invite-management permission.
- `target_email` is the normalized email snapshot.
- `user_id` is populated if a global user already exists at invitation creation; otherwise NULL.
- Do not create a usable global user or active membership merely by creating an invitation.
- On acceptance, re-check current inviter/tenant policy as required, verify the token and target email, and atomically consume the token plus create/activate the correct membership. If no global user exists, create the user with Argon2id password hash and required verification state in the same transaction.
- If a user already exists, do not overwrite their password or profile. Require proof of control of the invitation address and ensure it still matches the user's current normalized email.
- Role assignment must be derived from server-side policy and inviter authority, never trusted from client-supplied role IDs.

### EMAIL_VERIFICATION
- `user_id` and `target_email` bind the action to the current user/email pair.
- At consumption, re-read the user and verify the normalized current email still matches the snapshot.
- Set `email_verified_at` only after atomic successful consumption.
- Do not activate suspended users or memberships as a side effect.

### PASSWORD_RESET
- `user_id` and `target_email` bind the action to the current user/email pair.
- At consumption, re-read the user and verify the normalized current email still matches the snapshot.
- Verify and consume the token, then persist a newly encoded Argon2id hash in one transaction. Password hashing should be done before opening the transaction; revalidate/consume atomically inside it.
- Reset must not change user status, membership status, roles, or email verification state.
- Token consumption and password update must commit together or roll back together.

## 5. Token generation and storage

- Generate at least 32 cryptographically random bytes using the platform secure random generator.
- Encode the secret using URL-safe Base64 without padding for transport.
- Compute SHA-256 over the exact raw random bytes (or one canonical decoded representation) and store only the 32-byte digest.
- Compare digests in constant-time in application code after indexed lookup where applicable; do not compare raw bearer secrets in SQL.
- Bind purpose, target email, user, and tenant in the database row and validate all bindings during consumption.
- Never log raw tokens, token digests, passwords, password hashes, or Authorization headers.
- Token lifetime values are policy decisions and are intentionally not fixed in this schema proposal.

A high-entropy 256-bit random secret is not a human password; a fast cryptographic digest is appropriate for lookup because exhaustive guessing is computationally infeasible. This assumption depends on using a CSPRNG and never reducing token entropy.

## 6. Atomicity and concurrency

Consumption must be implemented as a short database transaction:
1. Locate the row by unique digest and expected purpose.
2. Lock the row with `SELECT ... FOR UPDATE`.
3. Verify it is not expired, consumed, or revoked.
4. Revalidate purpose-specific user/tenant/email bindings and current status/authorization.
5. Perform the business state change (user/membership/password/email verification).
6. Set `consumed_at` and commit.

Two concurrent consumers must not both succeed. A failed business operation must roll back token consumption. Never hold this transaction open while sending email or running Argon2id.

Invitation resend must serialize per tenant + normalized email, revoke prior outstanding invitation tokens, then insert a new digest. Concurrent resend/acceptance tests must establish deterministic outcomes.

Ambiguous commit outcomes must fail closed: do not blindly retry consumption or issue a success response/token when commit outcome is unknown. A safe retry protocol/idempotency response can be designed separately if needed.

## 7. Retention and cleanup

- Expired, consumed, and revoked records are security evidence and should not be deleted immediately.
- Define a documented retention duration before implementation.
- Cleanup must be bounded by batch size and indexed predicates; never perform an unbounded delete.
- Cleanup must not remove unexpired active tokens.
- Retention and deletion must be consistent with audit/privacy requirements.
- Do not put a scheduler into the application until retention and operational ownership are approved.

## 8. Migration implementation requirements

When this design is approved and converted into a migration:
- Use WMS migration location only.
- Name the next migration version after verifying the current highest version; V3 is expected given current V1 and V2.
- Never modify V1 or V2.
- Use InnoDB, utf8mb4, and the established MySQL 8.4 migration conventions.
- Add a Testcontainers MySQL 8.4 migration test that proves V1 + V2 + new migration apply cleanly from an empty disposable schema.
- Assert table/column/index/FK/CHECK metadata.
- Test valid purpose combinations and rejection of invalid purpose/binding combinations, duplicate digests, invalid consumed/revoked state, and FK violations.
- Test migration compatibility with existing V1+V2 state as well as a fresh empty schema.
- Never run Flyway, integration tests, or schema-changing commands against `zirowms_v1` or `zirologistics_db`.

## 9. Open decisions before DDL

The following require explicit review before writing V3 SQL:
1. Whether email verification is required before first login, and how invitation acceptance satisfies it.
2. Exact invitation, verification, and reset token lifetimes.
3. Whether invitations can target only existing tenants and which tenant statuses permit invitations.
4. Exact authorization permission for inviting users and which role assignments an inviter may grant.
5. Whether pending invitations for the same tenant/email are superseded or can coexist.
6. Retention duration for consumed/revoked/expired tokens.
7. Whether `created_by_request_id` is useful enough to retain or should be omitted.
8. Whether an invitation to an existing user should store the user FK at creation or resolve it only at acceptance.
9. Email delivery provider and outbox/retry design. Sending mail must not happen inside the token-consumption transaction.
10. Bootstrap of the first platform operator/tenant owner remains a separate operational workflow.

## 10. Explicit non-goals

- This proposal is not a migration and creates no database objects.
- No endpoint, Spring bean, repository, or service is implemented.
- No user, membership, or role is created.
- No token, password, email, or personal data is generated.
- No production database is accessed.
- No Redis or refresh-token/session-revocation functionality is added.
