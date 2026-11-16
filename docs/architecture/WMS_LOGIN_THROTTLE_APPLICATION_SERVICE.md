# WMS Login Throttle Application Service

**Status:** Application orchestration foundation implemented; not wired to a public login use case.  
**Scope:** WMS security application layer only.  
**Database:** Uses the existing semantic storage port and additive V2 adapter; no migration in this change.

## 1. Purpose

This layer coordinates the already-established throttle primitives without coupling application policy to HTTP, Servlet, JDBC, MySQL, or Redis APIs.

It composes:

- WmsThrottleKeyHasher for normalized-email ACCOUNT keys and canonical-IP SOURCE keys.
- WmsClientAddressResolver for trusted-proxy-aware client address resolution.
- WmsThrottleStoragePort for atomic shared state.
- WmsLoginThrottlePolicy for explicit policy inputs.
- Clock for deterministic time and fixed-window boundaries.

The service is deliberately not annotated as a Spring component and is not automatically enabled. A future login use case must explicitly construct it only after deployment policy and secret configuration are approved.

## 2. Request sequence

1. Normalize the submitted email and derive its ACCOUNT HMAC key.
2. Resolve the source address from the socket peer and forwarding header. Forwarded values are accepted only through the configured trusted-proxy resolver.
3. Derive the SOURCE HMAC key.
4. Atomically record the source request and read the account cooldown through recordSourceAttemptAndCheckAccountCooldown.
5. Apply the committed post-increment source count:
   - count less than or equal to configured maximum N: source policy allows the request;
   - count N+1 or greater: deny before user lookup and password verification.
6. If the account has an active cooldown, deny before user lookup and password verification.
7. Only an allowed decision carries an opaque, service-owned Attempt continuation.

The source request is recorded even when the account is already in cooldown. Source history is not cleared by a successful login.

## 3. Credential outcome

For an allowed attempt, the future authentication use case is responsible for credential verification and user status handling.

- Failed password: after password work, call recordFailedCredential.
- Unknown email: perform equivalent password-work using a separately reviewed dummy-hash strategy before recording the same account failure outcome. This application service does not implement dummy-hash verification and must not be treated as solving timing-based account enumeration by itself.
- Correct password but global user not ACTIVE: do not reset the account bucket and do not issue a tenant-selection token.
- Correct password and global user ACTIVE: call recordSuccessfulActiveUserAuthentication before issuing the tenant-selection token.
- The success reset removes only ACCOUNT rows for the active HMAC key version and digest. SOURCE rows remain.

The service does not authenticate passwords, load users, decide account status, issue tokens, or create HTTP responses.

## 4. Explicit policy inputs

WmsLoginThrottlePolicy requires all values from its caller:

- source fixed-window duration;
- inclusive maximum source requests;
- account-failure fixed-window duration;
- account failure threshold;
- account cooldown duration.

Durations must be positive whole seconds. No production defaults or numeric thresholds are introduced by this change. Windows are aligned to UTC epoch-second boundaries.

The account failure window is calculated when the failed credential outcome is recorded, not when the request first arrived. This prevents a long password verification that crosses a window boundary from being charged to the earlier window.

## 5. Failure and retry behavior

- Any runtime failure from shared throttle storage is surfaced as WmsThrottleUnavailableException; the future HTTP boundary must map it to a generic temporary-unavailable response.
- The caller must not continue user lookup, password verification, or token issuance when the pre-verification storage decision is unavailable.
- If an account failure write or successful-login reset has an ambiguous commit outcome, the caller must fail closed and issue no token.
- An Attempt is bound to the service instance that created it and accepts exactly one terminal outcome. It is consumed before the storage call, so the same request cannot blindly retry an operation whose commit result is unknown.
- No local in-memory fallback is provided.

## 6. Security boundaries and remaining work

This implementation does not yet provide:

- public credential-login or tenant-selection endpoints;
- dummy-hash password verification for unknown users;
- account lookup/password verification orchestration;
- deployment binding for the HMAC secret, key version, trusted proxy CIDRs, or policy values;
- numeric limits approved by representative load testing;
- transaction/statement timeout values, cleanup schedule, or retention policy;
- HTTP response mapping, audit events, or operational metrics.

These are separate decisions and must be completed before enabling public login. In particular, never log raw email, source IP, HMAC digest, password, or token values.

## 7. Verification scope

Unit tests cover inclusive source threshold semantics, account cooldown denial, successful-account reset while preserving source history, trusted-proxy handling, storage failure fail-closed behavior, service-owned attempts, and one terminal outcome per attempt.

The MySQL adapter's transaction/concurrency behavior remains covered by its separate disposable MySQL 8.4 Testcontainers integration tests. This application-service change does not access either user database and does not alter WMS V1 or V2 schema.
