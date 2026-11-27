# WMS Authentication Readiness Audit

**Status:** Historical authentication readiness review. Credential-authentication and tenant-selection adapters are implemented as opt-in WMS HTTP capabilities; production data provenance and deployment load evidence remain unverified.  
**Runtime:** WMS-only runtime (`wms` profile)  
**Schema:** Frozen V1; this audit makes no schema or runtime changes.  
**Decision:** Do not enable credential login for production until the blockers below are resolved.

## 1. Executive result

The repository now contains WMS credential-authentication and tenant-selection HTTP adapters, a WMS JWT security boundary, and MySQL-backed login-throttle storage. It still does **not** establish that existing WMS account rows contain Argon2id hashes, nor does it contain target-production Argon2id benchmark evidence.

Therefore:
- WMS credential and tenant-selection endpoints are implemented, but production enablement remains gated by hash provenance, benchmark, and operational security evidence.
- Do not reuse the legacy authentication service or its encoder against WMS identity records.
- Do not inspect or modify a production database as part of this repository audit.
- WMS V2 login-throttle persistence and subsequent identity-token/outbox migrations are additive; frozen V1 remains unchanged.

## 2. Evidence classification

| Area | Repository evidence | Status |
|---|---|---|
| Legacy password algorithm | Legacy `SecurityConfig` constructs `BCryptPasswordEncoder`; legacy `AuthServiceImpl.register` stores the injected encoder output in legacy `users.password`. | VERIFIED — code structure |
| WMS password storage | WMS `UserEntity` maps `users.password_hash` (VARCHAR(255)); WMS datasource is configured independently through `WMS_DB_URL`, `WMS_DB_USERNAME`, and `WMS_DB_PASSWORD`. | VERIFIED — code/schema mapping |
| Existing WMS hash format | No repository evidence establishes the format/provenance of values already stored in the WMS database. | UNVERIFIED |
| WMS password encoder | WMS-only `Argon2PasswordEncoder` configured with 16-byte salt, 32-byte output, parallelism 1, 19 MiB memory, and 2 iterations; tests check the PHC prefix and matching behavior. | VERIFIED — unit tests |
| Production cost benchmark | No target-deployment benchmark or expected concurrent-login capacity evidence found. | NOT VERIFIED |
| Shared login throttling | MySQL-backed WMS throttle storage and application service are implemented with Testcontainers integration coverage; no Redis dependency is introduced. | VERIFIED — source and integration tests |
| WMS auth endpoints | Credential authentication and tenant-selection routes exist under `/api/wms/v1/auth/credentials` and `/api/wms/v1/auth/tenant-selection`; protected registration is gated by WMS JWT and anonymous access by the exact public-route switch. | VERIFIED — source and HTTP contract tests |
| HTTP security | JWT-disabled mode denies all requests; JWT-enabled mode registers protected WMS routes, while the separate switch permits only the exact anonymous POST allowlist. | VERIFIED — security tests and Stage 13 CI |
| WMS schema for throttling/sessions | V1 is immutable and has no throttle/session tables; additive V2 provides login-throttle persistence. Durable refresh-token/session revocation is still not implemented. | VERIFIED — migration and repository audit |

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

### 4.1 Implemented baseline

WMS V2 provides the `wms_login_throttle_buckets` table. The WMS runtime includes a MySQL 8.4 adapter implementing `WmsThrottleStoragePort`, an application throttle service, privacy-preserving HMAC account/source keys, atomic transactional bucket updates, bounded account cooldowns, successful-account reset, and bounded cleanup operations. Credential authentication invokes the throttle before user lookup/password verification and records failed/successful outcomes. Testcontainers integration coverage exercises the MySQL adapter and authentication orchestration.

A process-local counter or local cache is not used as a substitute. No Redis dependency is introduced.

### 4.2 Remaining production-readiness work

The code-level implementation does not approve numeric production thresholds or establish production performance. Before production enablement:
- review source/account windows, limits, cooldown, retention, and cleanup cadence;
- benchmark Argon2id and the complete login path on representative deployment hardware;
- load-test concurrent requests, NAT/shared-IP behavior, and source/account threshold boundaries;
- validate HMAC key provisioning/rotation and trusted-proxy CIDRs;
- configure monitoring, alerting, database timeout budgets, and a throttle-store outage runbook;
- confirm the operational impact of fail-closed behavior during database outages.

V1 remains immutable; throttle persistence is additive V2. A managed shared Redis service is an alternative only if deployment operations explicitly adopts and supports it.

### 4.3 Minimum control design to validate operationally

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
- [x] MySQL-backed shared/durable throttle storage and service have integration coverage; production thresholds, load evidence, monitoring, and outage runbook still require deployment sign-off.
- [ ] Generic authentication errors and timing behavior are tested for unknown, inactive, malformed-hash, and wrong-password cases.
- [ ] Login success updates last-login metadata safely without leaking account existence.
- [ ] Login returns only a tenant-neutral selection token and safe membership display data.
- [ ] Tenant selection verifies the selection-token profile and re-resolves the chosen membership against the authenticated global user.
- [x] HTTP security enforces the exact anonymous POST allowlist; all other business/protected routes require WMS access tokens.
- [ ] Permission and tenant ownership enforcement is tested at service boundaries.
- [ ] No refresh token is issued until durable session persistence, rotation, replay detection, and revocation are implemented.
- [ ] Integration tests use disposable MySQL 8.4 Testcontainers only.

## 6. Scope of this audit

This readiness document does not itself change runtime behavior. The current repository already includes the credential/tenant-selection HTTP adapters and MySQL-backed throttle implementation. This audit does not:
- inspect or connect to `zirowms_v1` or `zirologistics_db`;
- change frozen V1 or any migration;
- add public self-registration, refresh-token issuance, or durable logout/session revocation;
- change legacy BCrypt authentication;
- claim production password-hash compatibility.

## 7. References

- OWASP Password Storage Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- OWASP API4:2023 — Unrestricted Resource Consumption: https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/
- RFC 9106 — Argon2 Memory-Hard Function for Password Hashing and Proof-of-Work Applications: https://www.rfc-editor.org/rfc/rfc9106.html
- Spring Security Password Storage: https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html
- Spring Security OAuth 2.0 Resource Server JWT: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html
