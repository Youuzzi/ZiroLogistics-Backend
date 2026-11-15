# WMS Authentication Readiness Audit

**Status:** Repository evidence review; production data provenance remains unverified.  
**Runtime:** WMS-only runtime (`wms` profile)  
**Schema:** Frozen V1; this audit makes no schema or runtime changes.  
**Decision:** Do not expose credential login until the blockers below are resolved.

## 1. Executive result

The repository contains a working WMS Argon2id encoder foundation and an isolated WMS HTTP security boundary. It does **not** establish that existing WMS account rows contain Argon2id hashes, and it does not contain a shared/durable login-throttling implementation.

Therefore:
- WMS login and tenant-selection endpoints remain blocked.
- Do not reuse the legacy authentication service or its encoder against WMS identity records.
- Do not inspect or modify a production database as part of this repository audit.
- No database migration, endpoint, or password rewrite is included in this change.

## 2. Evidence classification

| Area | Repository evidence | Status |
|---|---|---|
| Legacy password algorithm | Legacy `SecurityConfig` constructs `BCryptPasswordEncoder`; legacy `AuthServiceImpl.register` stores the injected encoder output in legacy `users.password`. | VERIFIED — code structure |
| WMS password storage | WMS `UserEntity` maps `users.password_hash` (VARCHAR(255)); WMS datasource is configured independently through `WMS_DB_URL`, `WMS_DB_USERNAME`, and `WMS_DB_PASSWORD`. | VERIFIED — code/schema mapping |
| Existing WMS hash format | No repository evidence establishes the format/provenance of values already stored in the WMS database. | UNVERIFIED |
| WMS password encoder | WMS-only `Argon2PasswordEncoder` configured with 16-byte salt, 32-byte output, parallelism 1, 19 MiB memory, and 2 iterations; tests check the PHC prefix and matching behavior. | VERIFIED — unit tests |
| Production cost benchmark | No target-deployment benchmark or expected concurrent-login capacity evidence found. | NOT VERIFIED |
| Shared login throttling | No Redis client, shared limiter, login-attempt persistence, or WMS throttling service found in the repository. | VERIFIED — repository search |
| WMS auth endpoints | No WMS credential login or tenant-selection endpoint exists. | VERIFIED — repository state |
| HTTP security | WMS filter chain requires authentication when JWT is enabled and denies all requests when disabled/missing. PR #25 is merged. | VERIFIED — CI |
| WMS V1 throttle/session schema | Frozen V1 contains no dedicated login-attempt, refresh-token, or session-revocation persistence. | VERIFIED — schema audit |

## 3. Password hash compatibility

### 3.1 Do not infer WMS hashes from legacy code

The legacy runtime uses a BCrypt encoder and a legacy `users.password` field. WMS has a separate runtime/data-source configuration and maps a distinct WMS `users.password_hash` field.

This proves the legacy application's current code path, **not** the algorithm used to create existing WMS account hashes. The two data sources must not be conflated.

### 3.2 New WMS password policy

The WMS encoder's current parameters meet the OWASP Password Storage Cheat Sheet's listed Argon2id minimum configuration:
- memory: 19 MiB;
- iterations: 2;
- parallelism: 1.

The configured parameters are a baseline, not a production performance sign-off. Benchmark on representative deployment hardware and under expected concurrent login load before enabling the endpoint.

### 3.3 Required compatibility gate

Before authenticating an existing WMS user:
1. Obtain an approved, read-only inventory of hash-format metadata from a controlled environment. Never print or export raw password hashes into logs, tickets, or repository artifacts.
2. Determine whether all rows use the WMS Argon2id PHC format, whether multiple formats exist, and whether any rows are malformed/unknown.
3. If the provenance is not positively established, fail closed for those accounts and define a user password-reset/re-enrollment path.
4. If a legacy WMS format is positively identified, implement an explicit format-specific verifier and a controlled rehash-on-success migration to Argon2id. Do not add speculative fallbacks.
5. Ensure malformed and unknown formats produce the same externally visible authentication failure as invalid credentials.

Do not pass a BCrypt hash to the Argon2id encoder, do not treat a hash as plaintext, and do not silently accept unknown encodings.

## 4. Login throttling readiness

### 4.1 Current gap

No shared/durable throttle exists in the current WMS backend. A process-local counter or local cache is insufficient for multiple application instances because each instance would maintain independent limits and resets.

### 4.2 Recommended direction

**Recommendation for the current architecture:** design an additive, database-backed WMS throttle slice (Flyway V2) using atomic MySQL operations, unless deployment operations confirms a managed shared Redis service is available and supported.

Reasons:
- MySQL is already a required WMS dependency.
- An atomic database operation can coordinate attempts across WMS instances.
- It avoids silently introducing an undeclared production Redis dependency.
- It keeps V1 immutable; V2 must be additive and separately reviewed.

This is a recommendation, not an implemented or approved migration. If a managed Redis service is already part of the deployment platform, compare its availability, atomicity, outage behavior, monitoring, and operational ownership before choosing it.

### 4.3 Minimum control design to specify before implementation

The detailed policy must define:
- account-scoped throttling so distributed source IPs cannot bypass account protection;
- source/network-scoped throttling to constrain high-volume attempts against many accounts;
- privacy-preserving account keys (keyed HMAC of normalized email; never store raw submitted email as a throttle key);
- trusted-proxy rules before accepting forwarded client IP headers;
- atomic concurrent updates and clear time-window semantics;
- bounded temporary delay/cooldown, avoiding permanent lockout and avoiding an attacker-triggerable indefinite account lock;
- generic authentication responses for unknown user, wrong password, inactive account, and throttled account, with consistent externally visible behavior;
- a documented database/cache outage policy that fails safely without causing an authentication bypass;
- retention and cleanup of expired throttle records;
- metrics and audit events that never contain passwords, raw hashes, bearer tokens, or full credential request bodies.

Do not finalize numeric thresholds in source code without a load test and an abuse-case review. The account and source controls should be evaluated together so one control does not become a denial-of-service primitive against legitimate users.

## 5. Endpoint release gates

Credential login must remain disabled until all of the following are true:

- [ ] Existing WMS password-hash formats and provenance are established through an approved controlled process, or affected users are assigned a reset path.
- [ ] Argon2id parameters are benchmarked against deployment hardware and expected concurrency.
- [ ] Shared/durable throttling implementation and outage policy are approved and tested under concurrent requests.
- [ ] Generic authentication errors and timing behavior are tested for unknown, inactive, malformed-hash, and wrong-password cases.
- [ ] Login success updates last-login metadata safely without leaking account existence.
- [ ] Login returns only a tenant-neutral selection token and safe membership display data.
- [ ] Tenant selection verifies the selection-token profile and re-resolves the chosen membership against the authenticated global user.
- [ ] HTTP security explicitly allows only the two intended auth routes; all business routes remain protected by access tokens.
- [ ] Permission and tenant ownership enforcement is tested at service boundaries.
- [ ] No refresh token is issued until durable session persistence, rotation, replay detection, and revocation are implemented.
- [ ] Integration tests use disposable MySQL 8.4 Testcontainers only.

## 6. Scope of this audit

This change adds documentation only. It does not:
- inspect or connect to `zirowms_v1` or `zirologistics_db`;
- change schema or migration files;
- add a login, registration, tenant-selection, refresh, or logout endpoint;
- change legacy BCrypt authentication;
- add Redis, a local cache limiter, or database throttle tables;
- claim production password-hash compatibility.

## 7. References

- OWASP Password Storage Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- OWASP API4:2023 — Unrestricted Resource Consumption: https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/
- RFC 9106 — Argon2 Memory-Hard Function for Password Hashing and Proof-of-Work Applications: https://www.rfc-editor.org/rfc/rfc9106.html
- Spring Security Password Storage: https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html
- Spring Security OAuth 2.0 Resource Server JWT: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
