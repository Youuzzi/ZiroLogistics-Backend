# Tenant MFA and Step-Up Authentication Contract

**Status:** Stage 16 architecture contract; implementation not yet enabled.
**Scope:** WMS tenant identities only. Platform operator MFA remains a separate security plane.
**Schema:** Additive Flyway migration only. Frozen WMS V1–V30 migrations must not be edited.
**Purpose:** Establish a verifiable tenant MFA proof before enabling high-risk tenant governance actions, including UMKM single-operator activation.

## 1. Verified current-state findings

The current WMS tenant authentication path is password-only:

- `POST /api/wms/v1/auth/credentials` verifies a global user's password and returns a tenant-selection token plus selectable memberships.
- `POST /api/wms/v1/auth/tenant-selection` exchanges that token and a selected membership for a tenant-bound access token.
- The tenant access token contains the subject, membership reference, credential version, token profile, and standard time/audience/issuer/JWT ID claims. It does not contain MFA assurance.
- `WmsAuthenticatedPrincipal` contains the resolved user, tenant, membership, and current permissions; it has no MFA timestamp or assurance field.
- `WmsJwtAuthenticationConverter` resolves live membership and permission state but does not establish or validate MFA.
- The current tenant identity action-token purposes are invitation, email verification, and password reset. They are not second-factor challenges and must not be repurposed as MFA.
- The platform operator security plane has its own MFA claims and principal. Those claims are not proof of tenant-user MFA and must never be accepted by tenant APIs.
- No tenant TOTP, WebAuthn/passkey, authenticator enrollment, recovery-code, or tenant step-up implementation was found in the current WMS source.

This is a source audit, not a claim that all deployments have MFA disabled at an infrastructure layer. WMS application-level tenant MFA is not implemented.

## 2. Security-plane separation

Tenant MFA must be implemented independently from platform operator MFA:

- Separate tenant-owned persistence, application services, HTTP routes, DTOs, tests, and cryptographic configuration.
- Never accept a platform JWT, platform `mfa` claim, platform operator ID, or platform MFA timestamp as tenant assurance.
- Tenant MFA factors belong to the global WMS user identity. A successful step-up proof is additionally bound to the currently selected tenant and membership.
- Re-resolve the active user and membership from the database at verification and again when consuming the proof for a protected action.
- Do not accept tenant ID, user ID, membership ID, factor ID, or assurance timestamp from an untrusted request as authoritative identity.

## 3. Factor policy

### Initial supported factor

Implement **TOTP (RFC 6238)** as the first tenant MFA factor, with a standards-compatible authenticator app.

- Use a 30-second time step, 6-digit codes, and a narrow bounded clock-skew window (at most one step on either side).
- Store the TOTP seed only as authenticated ciphertext using a dedicated tenant-MFA encryption key supplied by deployment secret management. Never store plaintext seeds, include them in logs, or return them after enrollment.
- Bind each factor to the global user; bind each challenge and proof to the active tenant membership.
- Prevent TOTP time-step replay by atomically recording the last accepted time step for each factor. A previously accepted time step must not be accepted again, including under concurrent requests.
- Apply durable account/factor/source throttling. Backend unavailability fails closed.
- Use constant-time comparisons where applicable and generic public failure responses.

WebAuthn/passkeys are a preferred future phishing-resistant factor, but are a separate implementation slice requiring credential registration, origin/RP-ID configuration, challenge validation, and browser integration. Do not claim phishing resistance for TOTP.

Email OTP is not an acceptable tenant MFA factor for privileged governance actions. Email verification remains an account lifecycle control, not MFA.

### Recovery

- Generate a bounded set of high-entropy recovery codes once during enrollment.
- Store only keyed cryptographic digests (or a reviewed password-hash representation); show plaintext codes only once.
- Each recovery code is single-use, consumed atomically, and audited without recording the code.
- Recovery-code use may establish a recovery event but must not silently disable MFA or enroll a new factor.
- Factor replacement/reset requires a separately designed high-assurance recovery procedure, notifications, cooldown, and audit. Do not implement password-only self-service MFA reset.
- Require at least one verified recovery path before allowing the user to finish enrollment, or explicitly block privileged operations until recovery is configured.

## 4. Enrollment lifecycle

Factor lifecycle states:

- `PENDING`: seed created and encrypted; not usable for step-up.
- `ACTIVE`: enrollment confirmed by a valid TOTP; eligible for step-up.
- `REVOKED`: permanently unusable; retained only as required for audit/retention.
- `LOCKED`: temporarily blocked by abuse policy; cannot satisfy step-up.

Enrollment must:

1. Require an authenticated tenant access token and recent password reauthentication.
2. Create a pending factor with an expiry and a cryptographically random factor identifier.
3. Return the provisioning URI/secret once over TLS, with no persistence or logging of plaintext response material.
4. Require a valid TOTP from the pending factor before activation.
5. Create recovery codes and display them once.
6. Write a tenant audit event for enrollment completion; do not log the seed or recovery codes.
7. Invalidate pending enrollment material on expiry, cancellation, repeated failures, or replacement.

MFA must not become active merely because a user requested enrollment or scanned a QR code.

## 5. Step-up challenge and proof

A tenant step-up is a distinct protocol, not a mutable claim on the ordinary ten-minute access token.

### Challenge

A challenge is created only for an authenticated, currently active tenant membership. Persist a high-entropy random challenge identifier/nonce digest, user ID, tenant ID, membership ID, requested purpose, expiry, attempt state, and creation time.

The challenge is:

- short-lived (recommended 2 minutes);
- single-use;
- purpose-bound to a stable server-side action class;
- bound to the authenticated user, tenant, membership, and current credential version;
- unusable after membership suspension, user deactivation, credential-version change, expiry, revocation, or successful consumption.

The request must not choose an arbitrary purpose string. The server maps a finite allowlist of action classes to challenge purposes.

### Verification

Verification requires a valid active TOTP factor and the challenge. On success, in one transaction:

- lock and validate the challenge and factor;
- validate the TOTP time step and atomically advance the factor replay watermark;
- revalidate active user and membership bindings and credential version;
- mark the challenge consumed;
- create a short-lived step-up proof record.

Failed attempts are counted durably and bounded. Unknown, expired, consumed, mismatched, replayed, locked, and invalid-code cases return a generic failure. Do not reveal factor enrollment status through unauthenticated or cross-membership responses.

### Proof consumption

A successful verification yields an opaque proof handle; store only its digest. The proof record is bound to:

- global user ID;
- tenant ID;
- membership ID;
- credential version;
- purpose/action class;
- verification timestamp and expiry;
- consumed/revoked state.

Proof validity is at most **5 minutes** from successful verification. It is one-time and purpose-specific. A proof for role assignment cannot authorize single-operator activation or a different action.

The protected application service must consume the proof inside the same database transaction as the protected state change, after locking/revalidating the proof and current membership. Proof consumption and the state change must commit or roll back together. A proof cannot be consumed in a controller and then passed as a boolean to a service.

Do not add `mfa_at` to the normal tenant access JWT as the authoritative proof. A reusable access-token claim would outlive the one-time action and cannot by itself prevent replay. If request authentication later exposes an assurance summary, it is informational only; the persisted proof remains authoritative for privileged commands.

## 6. Protected action classes

Initial step-up-required actions:

- activate or deactivate UMKM single-operator governance mode;
- change the selected single-operator role/grant set;
- enroll, revoke, replace, or recover tenant MFA factors;
- change tenant owner/admin governance assignments;
- other explicitly classified high-risk governance operations added through reviewed policy.

The server must enforce step-up in the application service, not only in route configuration or frontend UX.

Single-operator activation still requires all separate conditions:

- active tenant and membership;
- explicit governance permission;
- supported tenant governance profile and policy;
- fresh, purpose-bound tenant step-up proof;
- transactional grant ceiling and audit;
- no A1 approval, reversal, or other mandatory independent-actor permission;
- effective grant removal on deactivation;
- live authorization and persisted actor-evidence checks at workflow execution time.

MFA is an additional assurance control; it does not replace RBAC, SoD, tenant isolation, or independent actor validation.

## 7. Persistence design requirements

A later implementation PR must add reviewed additive migration(s), not edit V1–V30. The logical persistence model should include:

- tenant MFA factor records: user binding, factor public ID, factor type, encrypted seed/key version, lifecycle state, replay watermark, timestamps, lock state;
- recovery-code records: factor/user binding, unique digest, consumed timestamp, creation timestamp;
- step-up challenge records: challenge digest, user/tenant/membership binding, credential version, purpose, expiry, attempt count, consumed/revoked timestamps;
- step-up proof records: proof digest, full identity binding, purpose, verified/expiry timestamps, consumed/revoked timestamps.

Use foreign keys to existing user, tenant, and membership identities, with composite tenant-membership constraints where supported by the established schema. Add unique constraints and indexes for digest lookup, active factor lookup, expiry cleanup, and bounded abuse counters. Do not reuse the credential-login throttle buckets for MFA verification; MFA has its own independent account/factor/source bucket namespace. Enforce terminal-state and binding invariants with database constraints where practical.

Do not store raw TOTP seeds, raw recovery codes, raw challenge nonces, raw proof handles, or raw password-reauthentication grants. Use UTC `DATETIME(6)` persistence conventions already established by WMS migrations. Add migration clean-install and upgrade verification against disposable MySQL 8.4 Testcontainers.

**Implemented schema slices:** V31 now provides factor, recovery-code, step-up challenge/proof, dedicated MFA throttle, and tenant security-audit tables. V32 adds `wms_tenant_mfa_reauthentication_grants`: a digest-only, single-use, user/tenant/membership/credential-version/purpose-bound grant with a database-enforced maximum five-minute lifetime. It is intended to bridge successful password reauthentication to a specific sensitive MFA lifecycle operation; it is not an MFA proof and cannot satisfy step-up. The HTTP/application flow and durable throttle enforcement must be implemented before this table is used.

The exact table/column names and FK definitions are intentionally left for the schema implementation PR after a complete inspection of the existing identity and membership DDL.

## 8. Audit and privacy

Audit enrollment, activation, failed/locked factor events at an appropriate aggregate level, successful step-up, recovery-code use, factor revocation/replacement, proof consumption, and privileged action outcome.

Audit records must include actor user/membership, tenant, event type, purpose, correlation/request ID, outcome, and UTC event time where the existing audit contract supports them. Never include TOTP values, seeds, recovery codes, challenge nonces, proof handles, Authorization headers, or full provisioning URIs.

Apply retention and cleanup policies to expired challenges/proofs without deleting audit evidence required by retention policy.

## 9. Required verification before enablement

Unit tests:
- RFC 6238 known vectors and clock-boundary behavior;
- malformed/expired challenge rejection;
- invalid factor state rejection;
- one-step clock skew only;
- TOTP replay rejection;
- constant-time-safe comparison utility behavior where testable;
- challenge purpose and identity mismatch rejection;
- proof expiry, purpose mismatch, credential-version mismatch, and replay rejection;
- fail-closed behavior for missing keys and persistence failures.

MySQL 8.4 integration tests:
- enrollment activation transaction and pending-factor expiry;
- concurrent reuse of one TOTP time step permits at most one success;
- concurrent challenge verification permits at most one proof;
- concurrent proof consumption permits at most one protected action;
- proof consumption and protected state change roll back together;
- cross-tenant and cross-membership proof rejection;
- user deactivation, membership suspension, and credential-version change invalidate outstanding challenges/proofs;
- recovery-code one-time consumption and factor replacement lifecycle;
- audit writes and rollback semantics;
- expiry cleanup does not remove active or required audit records.

HTTP/security tests:
- tenant MFA endpoints are authenticated and WMS-only;
- platform tokens are rejected by tenant security and vice versa;
- no enrollment, challenge, or proof route is accidentally public;
- rate-limit backend/key failure denies safely;
- secrets and codes are absent from logs and error bodies;
- existing tenant and platform security chains remain isolated.

No production enablement until deployment key management, backup/rotation, clock synchronization, recovery operations, abuse monitoring, and support runbooks are reviewed.

## 10. Implementation sequence

1. **Contract:** establish tenant MFA factor, step-up, proof, recovery, and service-enforcement requirements.
2. **Persistence (in progress):** V31 factor/recovery/challenge/proof/throttle/audit storage; V32 password-reauthentication grant storage; replay-protection adapter implemented separately.
3. **TOTP domain/application:** complete pending enrollment, confirmation, durable throttling, recovery-code lifecycle, and step-up verification.
4. **Password reauthentication:** implement a tenant-authenticated, purpose-bound, one-time grant issuance and consumption flow with dedicated abuse controls.
5. **HTTP boundary:** authenticated tenant MFA endpoints, DTO validation, generic errors, and security tests.
6. **Step-up enforcement:** transactional proof consumption in privileged governance services.
7. **Single-operator activation:** implement only after tenant MFA is operational and verified.
8. **WebAuthn:** separate phishing-resistant factor slice with browser integration and migration compatibility.

No tenant MFA factor is enrolled, no user is forced into MFA, and no governance mode is activated by this architecture-only contract.
