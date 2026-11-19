# WMS Login Throttling Design

**Status:** Historical design proposal; MySQL V2 persistence, adapter, and credential-flow integration are now implemented. Production numeric policy and deployment evidence remain subject to sign-off.  
**Scope:** WMS credential-login abuse resistance only.  
**Schema:** Frozen V1 is immutable. Any persistent implementation must be an additive Flyway V2 migration.  
**Database safety:** All implementation and integration tests must use disposable MySQL 8.4 Testcontainers. Never connect to or mutate `zirowms_v1` or `zirologistics_db`.

## 1. Decision summary

At the time this proposal was written, the repository had no shared login-throttling implementation. The current implementation uses additive MySQL V2 persistence, the `MySqlWmsThrottleStorageAdapter`, and the WMS application throttle service integrated into credential authentication. A process-local counter or Caffeine cache remains unacceptable as the shared production control.

**Implemented adapter:** MySQL 8.4. Redis remains optional and is not introduced without a managed service and operational owner.

The design sections below record the security and concurrency rationale. Numeric production thresholds, load evidence, HMAC key rotation operations, monitoring, and the outage runbook remain deployment release gates.

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

## 4. Implemented V2 persistence shape

The additive V2 migration creates `wms_login_throttle_buckets` to store one row per bucket key and time window. The implemented columns are:

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

Required uniqueness is the complete logical bucket identity, for example bucket type + key version + digest + window start. Indexes must support lookup by that identity and cleanup by time. The V2 migration has been written and is frozen with the current implementation. Any future additive changes must follow the same schema conventions and must not rewrite V2 after deployment.

A fixed-window design is the simplest initial candidate. A sliding-window or token-bucket design may reduce boundary bursts but adds state/complexity. Select one deliberately and test its edge semantics. Do not combine multiple algorithms accidentally.

## 5. Atomicity and concurrency

A check-then-increment sequence implemented as separate SELECT and UPDATE statements is not safe: concurrent requests can all observe the same count and pass.

The implementation must use a single atomic database mutation per bucket (for example, a unique-key upsert with conditional counter/window updates) or a transaction with an explicit row lock and a well-defined insert-race strategy.

Use distinct semantics for the two dimensions:
- the source bucket counts every credential-login request and is atomically updated before user lookup/password verification;
- the account bucket tracks failed credential outcomes. Its active cooldown is checked before password verification; a failed password or unknown account atomically increments it after verification. A successful authentication clears/decays the account failure state according to the approved policy, but never clears source history.

This avoids counting a successful password as a failed account attempt. It also means already in-flight requests can finish after another request crosses the account threshold; the source bucket is the pre-verification control that limits parallel bursts. This residual concurrency window must be bounded and measured in tests/load tests rather than falsely described as zero.

Requirements:
- source bucket update and account cooldown check must have explicit transaction/consistency semantics;
- concurrent requests for the same bucket must not lose increments;
- the result returned to the service must be based on the post-update state;
- use database UTC time for persisted window/cooldown comparisons to avoid clock skew between application instances;
- use bounded transactions and sensible indexes;
- avoid unbounded lock waits and retry storms;
- test simultaneous requests from multiple service instances against the same disposable MySQL database.

If the account and source updates cannot be committed together, define and test the partial-failure behavior. Prefer one transaction so a failure cannot silently leave only one dimension enforced.

## 6. Evaluation order and behavior

Implemented credential-authentication flow:

1. Apply HTTP payload size, content-type, and basic request-shape validation.
2. Resolve the client source using the trusted-proxy policy.
3. Normalize email using `WmsEmailNormalizer` where possible.
4. Atomically increment/check the source bucket. If throttled, return the generic authentication failure envelope without revealing the source rule.
5. Check the account cooldown (when the email is normalizable). If throttled, return the same generic authentication failure envelope.
6. Perform user lookup, active-status check, and password verification only when permitted.
7. For a wrong password or unknown account, atomically increment the account failure bucket. Ensure both outcomes follow indistinguishable external response paths.
8. On successful authentication, atomically reset only the account failure state under the active HMAC key version before issuing a tenant-selection token; never erase source-abuse history.
9. Emit safe metrics/audit events without credentials or raw identifiers.

The source bucket counts every login request, including successful attempts. The account bucket counts failed credential outcomes (including unknown-account attempts). A successful authentication resets only ACCOUNT bucket history for the active HMAC key version; it never resets SOURCE history. The implementation must ensure the account cooldown check, failure increment, and success reset use the same normalized account identity and key version.

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

## 7.1 Bounded cooldown semantics

For a fixed account window, the cooldown is created exactly once when the atomic failure counter crosses the configured threshold. Later failures in the same window must not move the existing `blocked_until` timestamp forward.

This prevents repeated requests from extending a victim's cooldown indefinitely. The cooldown duration is measured from the threshold-crossing decision, not from every subsequent request. When the cooldown expires, the account may attempt authentication again; the source bucket remains an independent control. A new account window can independently cross its threshold and create a new cooldown.

The MySQL adapter must use the pre-increment counter value in its atomic upsert condition so exactly one concurrent request performs the threshold-crossing transition. Do not use a rolling `GREATEST(existingBlockedUntil, now + cooldown)` update for every post-threshold failure.

This policy does not yet approve numeric thresholds, window lengths, or cooldown durations. Successful-login reset semantics are defined in section 7.2 and must not erase source history.


## 7.2 Successful-login account reset

**Selected implementation policy for the next stage:** after the submitted credentials have been verified successfully and the global user is confirmed active, reset the account's failure history before issuing a tenant-selection token. The reset is an atomic delete of all ACCOUNT bucket rows matching the normalized account's active HMAC key version and digest, across fixed windows. It must not delete or modify any SOURCE bucket.

Rationale:
- A valid credential is stronger evidence than prior failed attempts; a legitimate user who proves possession of the correct password must not remain blocked by stale account failures.
- Removing all account windows prevents an older still-active blocked_until row from unexpectedly re-blocking the account after a successful login.
- Source history remains intact, so a successful login does not erase source-level password-spray/request pressure.

Concurrency semantics are defined by database commit order:
- A failed-attempt transaction committed before the successful-reset transaction is cleared by that reset.
- A failed-attempt transaction committed after the reset creates/increments failure state after the successful authentication event.
- A request that passed its pre-verification cooldown check before another request crossed the threshold may still finish password verification. If it proves the correct password, its successful reset may clear that concurrent account cooldown. This is intentional: valid credentials win over account-lockout state, while the source bucket continues to constrain request volume.
- Do not claim that in-flight requests are cancelled when a cooldown is created.

This reset operation is implemented by `WmsThrottleStoragePort.resetAccountFailures` and `MySqlWmsThrottleStorageAdapter`, with credential-orchestration and MySQL integration coverage. During key rotation, the reset must cover every active/overlap key version used by account cooldown checks; until multi-version rotation is implemented, the current single-active-key configuration is the only supported case.


## 8. Outage behavior

The throttle is part of the credential-authentication security boundary.

**Implemented fail-closed behavior:** if the throttle store cannot be read or updated, password authentication does not proceed. The application surfaces a generic throttle-unavailable failure for safe HTTP mapping; it does not reveal account existence or store internals. The security boundary must remain fail-closed during a database outage.

The service must:
- set strict database timeouts;
- avoid retrying authentication writes without idempotent semantics;
- expose operational health/metrics for throttle-store failures;
- not fall back to a local in-memory limiter as if that were equivalent protection;
- document how operators restore service and inspect failures without accessing raw credentials.

This outage choice should be confirmed against the production availability objective before release.


## 8.1 Transaction boundaries and timeouts

Keep each storage operation in its own short transaction:
- pre-verification: source increment plus account cooldown lookup in one transaction; commit before user lookup and Argon2id verification;
- failed credential outcome: one atomic account failure increment/cooldown transition transaction after password verification;
- successful authentication: one atomic account reset transaction after password verification and active-user validation, before token issuance;
- cleanup: independent bounded batch transaction, never on the login request path.

Never hold a database transaction or row lock while performing Argon2id hashing/verification, calling external services, or signing/returning tokens. This avoids holding locks across intentionally expensive CPU work.

Configure bounded transaction and JDBC statement timeouts. The transaction timeout, statement timeout, and MySQL lock-wait ceiling must be aligned so a request cannot wait indefinitely and the application does not retry in a tight loop. Numeric timeout values remain deployment/SLO decisions and must be load-tested; do not invent production values from the local laptop benchmark.

## 8.2 Ambiguous commit and retry policy

A connection failure during commit can leave the caller unable to know whether the mutation committed. The current storage contract has no per-operation idempotency identifier, so blindly retrying an increment can double-count a source request or failed credential outcome. A retry of a success-reset delete can also erase a later failed attempt.

Therefore:
- do not automatically retry any throttle state mutation after an exception where commit outcome may be unknown;
- treat an unknown pre-verification commit outcome as a throttle-store failure: do not perform user lookup/password verification and return a generic temporary 503;
- treat an unknown failed-account-write outcome as a throttle-store failure: do not issue the normal credential-failure response or token; return generic temporary 503;
- treat an unknown successful-account-reset outcome as incomplete login: do not issue a tenant-selection token; return generic temporary 503;
- a later client request is a new login attempt and is counted independently; do not replay the prior mutation internally;
- distinguish known transaction rollback from unknown commit outcome in internal metrics/logs, without logging account/source identifiers or secrets.

If future requirements demand safe automatic retries, add a separately designed operation-id/idempotency contract and additive V2 persistence with concurrency tests. Do not retrofit blind retries into the existing adapter.

## 8.3 Source threshold boundary

The source counter increments before password verification and returns the committed post-increment count. Interpret the configured source limit as the maximum number of requests permitted in one source fixed window: counts less than or equal to the limit may proceed; the first request whose post-increment count is greater than the limit is denied before user lookup/password verification. Thus the request that consumes the final permitted slot is allowed, and the next request is denied. The numeric limit and window duration remain unapproved.

The account failure threshold has different semantics: the failed credential outcome that makes the post-increment account failure count equal to the configured threshold creates the bounded cooldown. That credential request has already been verified as failed and receives the generic authentication-failure envelope; subsequent requests observe the cooldown before password verification. Numeric threshold/window/cooldown values remain unapproved.


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
- successful authentication atomically resets account failure rows but does not erase source-abuse history;
- success-reset and concurrent failed-attempt commit ordering is deterministic and tested;
- ambiguous commit outcomes never trigger blind mutation retries or token issuance;
- source threshold allows the configured maximum and denies the next request;
- concurrent updates do not lose increments;
- concurrent requests cannot bypass the threshold;
- account and source updates roll back or fail consistently;
- database unavailable/timeout fails closed;
- cleanup removes only expired rows in bounded batches;
- logs and metrics contain no raw email, IP, password, hash, or token;
- multi-instance service test shares the same database state.

## 12. Remaining production release sequence

1. Approve source/account thresholds, windows, cooldown, retention, and cleanup policy using expected workload and abuse-case review.
2. Benchmark Argon2id and the complete credential-login path on representative deployment hardware.
3. Complete load testing for concurrent requests, NAT/shared-IP scenarios, and threshold boundaries.
4. Provision and rehearse HMAC key rotation and trusted-proxy configuration.
5. Add production metrics, alerts, cleanup scheduling, bounded JDBC/transaction timeouts, and the throttle-store outage runbook.
6. Complete password-hash provenance review and production security sign-off.

The MySQL V2 migration, adapter, service, and credential endpoint integration are already implemented; do not create a duplicate throttle schema or another adapter without a separate decision.

## 13. References

- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- OWASP API4:2023 — Unrestricted Resource Consumption: https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/
- OWASP Logging Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- MySQL 8.4 Reference Manual — INSERT ... ON DUPLICATE KEY UPDATE: https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html
- MySQL 8.4 Reference Manual — InnoDB Locking: https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html
