# WMS Credential Authentication Orchestration

**Status:** Internal application use case only; not registered as a Spring bean and not exposed over HTTP.  
**Runtime target:** WMS profile only.  
**Schema:** No migration; frozen V1 and additive V2 throttle schema are unchanged.  
**Database safety:** Tests use mocks/unit fixtures or disposable Testcontainers only. No user database is accessed.

## 1. Purpose

This slice composes the previously implemented identity, Argon2id, dummy-password verification, throttle, and tenant-selection token services into one explicit credential-authentication use case.

It does not create a login controller, public route, request DTO, response DTO for HTTP, tenant-selection endpoint, or production bean wiring. Deployment policy and secrets remain explicit prerequisites.

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

- No controller, URL, HTTP status mapping, validation DTO, or CORS change.
- No Spring auto-wiring of WmsCredentialAuthenticationService.
- No production throttle policy, HMAC secret, key-version rotation policy, or trusted-proxy CIDRs selected.
- No Argon2id parameter changes.
- No existing password hash inspection, conversion, or migration.
- No refresh token, logout/revocation, session persistence, or audit-event persistence.
- No V1 schema changes and no new Flyway migration.
- No change to legacy authentication.

## 6. Required gates before HTTP integration

1. Verify the provenance and supported formats of existing WMS users.password_hash values through an approved, non-production data fixture or controlled migration plan. Never dump hashes into logs or commit them.
2. Approve deployment values for source/account windows, limits, cooldown, JDBC/transaction timeouts, HMAC key material/version, and trusted proxy CIDRs.
3. Add a WMS-only composition configuration that is fail-closed when any required property or throttle storage is absent.
4. Add HTTP request validation and a generic authentication error contract; distinguish infrastructure unavailability without disclosing account existence.
5. Add integration tests using disposable MySQL 8.4 Testcontainers for repository projection, throttle ordering, and full authentication orchestration.
6. Complete security review and load testing before exposing a public login endpoint.

## 7. Test coverage in this slice

Unit tests cover:
- unknown email dummy verification and account-failure recording;
- wrong password rejection;
- inactive global user rejection after password work;
- malformed stored hash fallback to dummy work;
- successful reset before membership lookup and token issuance;
- reset failure preventing membership lookup/token issuance;
- throttle rejection stopping before identity lookup or password verification.