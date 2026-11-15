# WMS Login Throttling Design

**Status:** Design proposal; implementation and numeric policy are not approved by this document.  
**Scope:** WMS credential-login abuse resistance only.  
**Schema:** Frozen V1 is immutable. Any persistent implementation must be an additive Flyway V2 migration.  
**Database safety:** All implementation and integration tests must use disposable MySQL 8.4 Testcontainers. Never connect to or mutate `zirowms_v1` or `zirologistics_db`.

## 1. Decision summary

The repository currently has no shared login-throttling implementation. A process-local counter or Caffeine cache cannot coordinate multiple backend instances and is not an acceptable production control.

**Proposed default:** use an additive MySQL-backed throttle store unless deployment infrastructure confirms that a managed, shared Redis service is already available and operationally supported.

This document deliberately does not create V2 DDL, choose final thresholds, or enable login. It records a design that can be reviewed before implementation.

## 2. Threat model and objectives

The throttle must reduce:
- password guessing against one account from many source addresses;
- password guessing against many accounts from one source;
- distributed attempts that rotate between accounts and addresses;
- concurrent-request races that bypass a naïve read-then-write counter;
- account enumeration through different error messages;
- privacy exposure from storing submitted email addresses or raw IP addresses.

The throttle is not a substitute for password hashing, MFA, edge/WAF controls, bot detection, monitoring, or network-level abuse controls. It must not become a permanent account-lockout primitive that lets an attacker deny service to a victim.

## 3. Proposed key dimensions

Use two independent dimensions, evaluated together:

1. **Account bucket:** keyed HMAC of the normalized submitted email. This remains available even when the email does not match a user row, preventing the account-existence lookup from deciding whether throttling applies.
2. **Source bucket:** keyed HMAC of a canonical client network identity derived from the remote address under a documented trusted-proxy policy.

For invalid/un-normalizable email input, apply the source bucket and request-shape limits; do not create an account bucket from arbitrary unbounded input.

### 3.1 Privacy and key management

- Never persist raw email, password, Authorization header, access token, raw password hash, or untrusted forwarded-address text in throttle rows.
- Use HMAC-SHA-256 with a dedicated secret key from deployment secret management; do not reuse JWT signing keys or password-hashing salts.
- Store a key-version identifier with each bucket so HMAC keys can be rotated deliberately.
- Normalize email using the existing `WmsEmailNormalizer` policy before HMAC. Do not independently implement a second normalization rule.
- Canonicalize IPv4/IPv6 addresses before HMAC. Only honor forwarding headers from explicitly trusted proxy hops; otherwise use the socket peer address.
- Document key rotation: during a bounded overlap, either accept old and new bucket keys or intentionally reset throttle history with an operationally approved procedure. Never log the secret.

## 4. Proposed persistence shape (conceptual only)

An additive table such as `wms_login_throttle_buckets` may store one row per bucket key and time window.

Conceptual columns:

| Column | Purpose |
|---|---|
| `id` | Internal primary key |
| `bucket_type` | ACCOUNT or SOURCE |
| `key_version` | HMAC key version |
| `bucket_key_hash` | Fixed-length HMAC digest; never raw identifier |
| `window_started_at` | UTC window start |
| `attempt_count` | Atomic counter |
| `blocked_until` | Optional bounded cooldown end |
| `created_at`, `updated_at` | UTC audit timestamps |

Required uniqueness is the complete logical bucket identity, for example bucket type + key version + digest + window start. Indexes must support lookup by that identity and cleanup by time. Exact column types, collation, FK policy, naming, and constraints must be reconciled with the frozen schema conventions before writing V2.

A fixed-window design is the simplest initial candidate. A sliding-window or token-bucket design may reduce boundary bursts but adds state/complexity. Select one deliberately and test its edge semantics. Do not combine multiple algorithms accidentally.

## 5. Atomicity and concurrency

A check-then-increment sequence implemented as separate SELECT and UPDATE statements is not safe: concurrent requests can all observe the same count and pass.

The implementation must use a single atomic database mutation per bucket (for example, a unique-key upsert with conditional counter/window updates) or a transaction with an explicit row lock and a well-defined insert-race strategy.

Requirements:
- account and source buckets must both be evaluated and updated consistently for each login attempt;
- concurrent requests for the same bucket must not lose increments;
- the result returned to the service must be based on the post-update state;
- use database UTC time for persisted window/cooldown comparisons to avoid clock skew between application instances;
- use bounded transactions and sensible indexes;
- avoid unbounded lock waits and retry storms;
- test simultaneous requests from multiple service instances against the same disposable MySQL database.

If the account and source updates cannot be committed together, define and test the partial-failure behavior. Prefer one transaction so a failure cannot silently leave only one dimension enforced.

## 6. Evaluation order and behavior

Proposed request flow:

1. Apply HTTP payload size, content-type, and basic request-shape validation.
2. Resolve the client source using the trusted-proxy policy.
3. Normalize email using `WmsEmailNormalizer` where possible.
4. Atomically account for the source attempt and, when normalizable, the account attempt.
5. If either bucket is currently throttled, return the same generic authentication failure envelope used for invalid credentials; do not reveal which dimension triggered it.
6. Perform user lookup, active-status check, and password verification only when permitted.
7. Record failed credential outcomes against the account dimension. Ensure unknown account and wrong password follow indistinguishable external response paths.
8. On successful authentication, clear or decay only the account failure state according to the approved policy; do not erase source-abuse history merely because one credential was valid.
9. Emit safe metrics/audit events without credentials or raw identifiers.

The exact placement of source-attempt counting and failed-account counting needs a precise implementation contract. In particular, avoid counting a successful login as a failed account password attempt, while still counting it as source request volume.

## 7. Threshold and cooldown policy

No numeric thresholds are approved yet. Select them only after:
- expected legitimate login volume and user population are estimated;
- a representative Argon2id benchmark is available;
- a load test measures concurrent login behavior;
- abuse scenarios are tested (single account, password spray, distributed source, NAT/shared office IP);
- the product's user-support and incident-response process is understood.

Policy requirements:
- use short rolling/fixed windows with bounded temporary cooldowns;
- avoid permanent account lockout;
- avoid allowing a single source to lock out many unrelated accounts;
- combine account and source controls, rather than relying on either alone;
- provide progressive friction or bounded delay where appropriate;
- return generic responses that do not reveal whether the account exists;
- ensure an attacker cannot extend a victim's cooldown indefinitely by continuously sending attempts;
- ensure legitimate users behind NAT/shared IPs are not disproportionately blocked;
- provide documented administrative recovery that does not expose a bypass endpoint.

## 8. Outage behavior

The throttle is part of the credential-authentication security boundary.

**Proposed behavior:** if the throttle store cannot be read or updated, do not proceed with password authentication. Fail closed with a generic temporary service-unavailable response (503), without revealing account existence or store internals. This avoids silently disabling abuse controls during a database outage.

The service must:
- set strict database timeouts;
- avoid retrying authentication writes without idempotent semantics;
- expose operational health/metrics for throttle-store failures;
- not fall back to a local in-memory limiter as if that were equivalent protection;
- document how operators restore service and inspect failures without accessing raw credentials.

This outage choice should be confirmed against the production availability objective before release.

## 9. Retention and observability

- Expire old bucket rows after the approved maximum window plus a cleanup buffer.
- Cleanup must be bounded/batched and must not block login traffic.
- Track aggregate counters: allowed, throttled, store failures, source-bucket pressure, account-bucket pressure, and cleanup outcomes.
- Do not tag metrics with raw email, IP address, HMAC digest, or other high-cardinality identifiers.
- Security audit events should record outcome category and correlation ID only; never log passwords, hashes, bearer tokens, or full request bodies.

## 10. Redis alternative and decision gate

If deployment already operates managed Redis, compare it with MySQL on:
- availability and ownership (including production support);
- atomic multi-key updates (Lua script/transaction semantics);
- persistence and eviction configuration;
- failover behavior and data-loss window;
- network isolation, TLS, authentication, secret rotation;
- metrics, alerting, backup/restore, and cost;
- outage policy and consistency across all WMS instances.

Choose Redis only if it is a supported shared production dependency with a documented atomic algorithm and outage policy. A developer-local Redis container or a newly introduced unmanaged service is not sufficient evidence.

If no such managed service exists, the additive MySQL V2 design remains the default candidate because MySQL is already a required WMS dependency. This recommendation is not permission to alter V1.

## 11. Required tests before login endpoint

All persistence tests must use disposable MySQL 8.4 Testcontainers, never either user database.

Minimum test matrix:
- first request creates the expected bucket(s);
- same account across multiple source addresses increments one account bucket;
- one source across multiple accounts increments one source bucket;
- unknown email and known email use the same account-bucket construction;
- invalid email still receives source protection;
- normalized email variants resolve to the same account bucket;
- trusted proxy and untrusted forwarded-header cases;
- threshold boundary and cooldown expiry;
- cooldown is bounded and cannot be extended indefinitely by attacker traffic;
- successful authentication does not erase source-abuse history;
- concurrent updates do not lose increments;
- concurrent requests cannot bypass the threshold;
- account and source updates roll back or fail consistently;
- database unavailable/timeout fails closed;
- cleanup removes only expired rows in bounded batches;
- logs and metrics contain no raw email, IP, password, hash, or token;
- multi-instance service test shares the same database state.

## 12. Implementation sequence

1. Confirm deployment has no supported managed Redis dependency, or complete the Redis-vs-MySQL decision record.
2. Approve the threshold/cooldown and outage policy after workload and abuse-case review.
3. Finalize additive V2 DDL and migration naming. Do not modify V1.
4. Implement a WMS-profile-only throttle repository/service with atomic MySQL operations.
5. Add Testcontainers integration and concurrency tests.
6. Add safe metrics, cleanup, and operational documentation.
7. Benchmark Argon2id and the combined login path.
8. Only then implement credential login and expose the explicitly allow-listed login route.

## 13. References

- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- OWASP API4:2023 — Unrestricted Resource Consumption: https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/
- OWASP Logging Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- MySQL 8.4 Reference Manual — INSERT ... ON DUPLICATE KEY UPDATE: https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html
- MySQL 8.4 Reference Manual — InnoDB Locking: https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html
