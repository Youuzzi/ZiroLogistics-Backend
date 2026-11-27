# WMS Credential Authentication Orchestration

**Status:** Implemented application use case; explicitly wired by `WmsCredentialAuthenticationConfiguration` and exposed through the WMS authentication HTTP adapter when the required security gates are enabled.  
**Runtime target:** WMS profile only.  
**Schema:** No migration; frozen V1 and additive V2 throttle schema are unchanged.  
**Database safety:** Tests use mocks/unit fixtures or disposable Testcontainers only. No user database is accessed.

## 1. Purpose

This slice composes the identity, Argon2id, dummy-password verification, throttle, and tenant-selection token services into one explicit credential-authentication use case. It is wired through the WMS credential-authentication configuration and HTTP adapter; deployment policy and secrets remain explicit prerequisites.

## 2. Credential flow

1. WmsLoginThrottleService.beginAttempt normalizes and HMAC-hashes the submitted email, resolves the client source using the configured trusted-proxy resolver, and commits the source-attempt/account-cooldown decision before user lookup.
2. Source-limit or account-cooldown rejection stops immediately with the same generic credential rejection used for bad credentials.
3. WmsUserLookupService.findByEmail performs the canonical global-user lookup.
4. Unknown email performs one dummy Argon2id verification, records an account failure, and returns generic credential rejection.
5. Known email verifies the stored hash. Suspended/pending users still perform password verification before generic rejection. A malformed or unsupported stored hash is never accepted; the dummy verifier performs substitute password work before recording failure.
6. Wrong password or non-ACTIVE global user records an account failure and returns generic credential rejection.
7. Only a correct password for an ACTIVE global user may reset account throttle history.
8. The reset must return successfully before selectable memberships are queried and before a tenant-selection token is issued.
9. The response object contains the short-lived tenant-selection token and only active memberships belonging to an ACTIVE tenant. It exposes public IDs and safe display fields, not internal database IDs or permissions.

## 3. Security and failure invariants

- No user lookup or Argon2 verification occurs before the source/account pre-check.
- The throttle attempt is terminal: either a failed credential is recorded or a successful active-user reset is committed. No retry is attempted after an ambiguous storage outcome.
- Any throttle storage failure propagates and prevents token issuance.
- A rejected throttle decision does not query the user repository.
- Unknown email, wrong password, malformed hash, and inactive global user share one application-level credential rejection.
- The dummy verifier's result is never used as an authentication decision.
- Membership selection is still a separate future operation; this use case does not choose a tenant.
- Membership list is derived from database state and includes only ACTIVE membership rows for ACTIVE tenants.
- The application use case receives the numeric socket peer separately from X-Forwarded-For; only the configured trusted-proxy resolver may interpret forwarded metadata.
- No raw email, password, IP, HMAC digest, or password hash is added to logs or result objects by this slice.

## 4. Persistence query

WmsLoginMembershipRepository is a read-only projection query over tenant_memberships and tenants. It returns membership public ID, tenant public ID, tenant code, and tenant display name. It filters both membership and tenant status to ACTIVE.

The query intentionally omits internal numeric identifiers and role/permission assignments. Those are resolved later by the existing membership access service when the user explicitly selects a membership.

## 5. Deliberate non-goals

- No public self-registration or anonymous tenant creation.
- No refresh token, logout/session revocation, or durable session lifecycle.
- No change to the legacy authentication runtime.
- No V1 schema changes; the WMS throttle persistence is the existing additive V2 migration.
- No claim that existing password hashes have verified provenance or that production Argon2id cost is approved.

## 6. Remaining gates before production enablement

1. Verify the provenance and supported formats of existing WMS `users.password_hash` values through an approved, non-production data fixture or controlled migration plan. Never dump hashes into logs or commit them.
2. Approve source/account windows, limits, cooldown, JDBC/transaction timeouts, HMAC key material/version, and trusted proxy CIDRs for the actual deployment.
3. Benchmark Argon2id and the complete login path on representative deployment hardware; complete concurrent load and abuse-case testing.
4. Confirm generic HTTP errors, DTO validation, request-size limits, CORS allowlist, and absence of credential/identifier logging.
5. Configure operational metrics, alerts, cleanup scheduling, and the fail-closed throttle-store outage runbook.
6. Do not expose refresh tokens or claim logout revocation until durable session persistence, rotation, replay detection, and revocation exist.

## 7. Test coverage in this slice

Unit tests cover:
- unknown email dummy verification and account-failure recording;
- wrong password rejection;
- inactive global user rejection after password work;
- malformed stored hash fallback to dummy work;
- successful reset before membership lookup and token issuance;
- reset failure preventing membership lookup/token issuance;
- throttle rejection stopping before identity lookup or password verification.