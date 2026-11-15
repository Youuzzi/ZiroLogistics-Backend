# WMS Login Throttling Storage Port and Redis Readiness

**Status:** Architecture direction approved; semantic storage port implemented; MySQL adapter and numeric security policy pending.  
**Date:** 2026-11-15  
**Scope:** Storage boundary for WMS credential-login throttling.  
**Related design:** [WMS Login Throttling Design](WMS_LOGIN_THROTTLING_DESIGN.md)  
**Schema rule:** WMS V1 is frozen. Persistent changes are additive Flyway V2 only.  
**Database safety:** All implementation and integration tests use disposable MySQL 8.4 Testcontainers. Never connect to or mutate `zirowms_v1` or `zirologistics_db`.

## 1. Decision

ZiroLogistics currently has no production server and no confirmed managed Redis service or Redis operational ownership.

The approved initial direction is:

1. Implement login throttling with MySQL as the first shared persistence adapter.
2. Define a narrow storage port around the security operations required by the login-throttling use case.
3. Keep the application/use-case layer independent of MySQL, Redis, Spring Data, and vendor-specific commands.
4. Do not add a Redis dependency, Redis container, Redis deployment, or Redis adapter to the current runtime.
5. Re-evaluate a Redis adapter when a production deployment has a concrete shared-cache/rate-limit requirement and an operational owner.

This is a deliberate portability boundary, not a commitment that Redis will eventually be required. MySQL remains the source of truth for WMS business data. Redis, if introduced, is supporting infrastructure and must not become the authoritative inventory or transaction ledger.

## 2. Why MySQL first

MySQL is already a required WMS dependency. Using it avoids introducing another service before production needs, monitoring, backup, network security, and operational ownership exist.

A process-local map or Caffeine cache is not an acceptable substitute: separate application instances would not share counters, restarts would erase state, and attackers could distribute requests across instances.

The initial MySQL implementation must still be designed for concurrency. A read-then-write counter is not acceptable. Updates and decisions must be atomic at the logical operation boundary, using database transactions, unique constraints, conditional updates/upserts, or row locking with tested insert-race handling.

## 3. Implemented semantic storage port

The application-owned contract is `WmsThrottleStoragePort` in the WMS throttling application package. It exposes only domain keys, UTC `Instant` values, policy inputs, and application result records. It does not expose JDBC, JPA, MySQL, Redis, or provider SDK types.

The contract currently defines:

- **Pre-verification operation:** atomically increment the source bucket and check the account's active cooldown at the supplied decision instant. The returned source count is the committed post-increment count.
- **Failed credential outcome:** atomically increment the account failure bucket; the adapter receives the approved threshold and absolute cooldown instant as explicit policy inputs.
- **Bounded cleanup:** delete at most the supplied batch size, retaining rows with a cooldown that remains active at the supplied decision instant.

The contract requires SOURCE and ACCOUNT key types in their corresponding positions. Result records reject invalid counters and expose a deterministic cooldown evaluation method.

### Policy deliberately not embedded

The port does not choose source thresholds, account thresholds, fixed-window lengths, cooldown durations, retention periods, or retry policy. No numeric values are approved. The future use case must calculate UTC window boundaries and supply policy values explicitly.

### Remaining contract decisions before login integration

- Successful-login account-state clearing/decay semantics, including races with in-flight failed attempts.
- Idempotency/retry semantics for ambiguous database commit outcomes.
- Transaction timeout and lock-wait budget.
- Whether source thresholds require an additional persisted decision field or can be evaluated safely from the committed count.
- Cleanup schedule and retention duration.

These must be resolved and tested before the port is integrated into credential verification.

### 3.1 Atomicity requirement

The pre-verification operation must not implement separate unprotected "check source", "increment source", and "check account" calls in application code. The adapter must provide the required atomicity and a consistent decision for both dimensions.

The failed-outcome operation must prevent lost account increments under concurrent requests. If the chosen schema/algorithm requires multiple statements, document lock ordering and unique-key race handling. Tests must prove the contract, not merely verify individual repository methods.

A future Redis adapter must meet the same observable contract, potentially using a Lua script or another atomic Redis primitive. If it cannot meet the contract, it is not a valid adapter.

## 4. Module boundary

Target dependency direction:

```text
WMS Login Use Case
       |
       v
Throttle Storage Port (application-owned contract)
       |
       +-------------------------+
       |                         |
       v                         v
MySQL Adapter (initial)     Redis Adapter (future, optional)
       |                         |
       v                         v
Flyway V2 / MySQL           Shared managed Redis
```

The MySQL adapter is the only planned implementation in the initial delivery. The Redis adapter is not a stub and must not be added merely to demonstrate extensibility.

Keep the boundary inside the WMS module and WMS runtime profile. Do not let legacy application components discover or instantiate WMS throttling infrastructure.

## 5. Data and schema boundaries

- Do not edit the frozen V1 migration or source DDL.
- Add only the minimum V2 persistence required by the selected MySQL algorithm.
- Do not store raw email, raw IP, password, password hash, bearer token, or untrusted forwarded-header text in throttle records.
- Use dedicated HMAC-SHA-256 key material, separate from JWT signing keys and password-hashing parameters.
- Keep key versioning explicit and define rotation behavior before production.
- Use UTC database time for persisted windows and cooldown comparisons.
- Define bounded retention and batched cleanup.
- Do not introduce a general-purpose Redis/cache schema or broad infrastructure abstraction as part of this feature.

## 6. Failure and security behavior

The throttle store is part of the authentication security boundary. If required state cannot be read or updated, credential verification must not proceed. The proposed behavior is fail closed with a generic temporary service-unavailable response. No process-local fallback may be represented as equivalent protection.

Externally visible responses must not reveal whether an account exists, which bucket triggered the decision, or whether MySQL/Redis is unavailable. Internal logs and metrics must avoid credentials, raw identifiers, HMAC values, and high-cardinality labels.

The port must preserve the two independent dimensions:
- source bucket counts every credential-login request;
- account bucket tracks failed credential outcomes and its bounded cooldown.

Thresholds and cooldown values remain unapproved until Argon2id benchmarking, expected legitimate volume, NAT/shared-IP scenarios, abuse tests, and load tests are reviewed.

## 7. Redis introduction gate

Redis may be considered when one or more concrete requirements justify it, such as:
- multiple production application instances with high login-throttle write volume;
- measured MySQL contention attributable to throttle operations;
- an existing managed Redis service with operational ownership;
- another approved WMS use case requiring shared low-latency ephemeral state.

Before adoption, evaluate:
- managed-service availability and support ownership;
- atomic multi-key semantics matching the port contract;
- persistence, eviction, failover, and data-loss behavior;
- TLS, network isolation, authentication, secret rotation;
- metrics, alerting, backup/restore, and recovery procedures;
- total cost and the behavior during Redis outage.

A local Docker Redis instance or an unowned self-managed service is not enough to approve production adoption. If Redis is introduced, it must be an explicit deployment capability, not an implicit application default. Configuration must fail safely when the selected adapter is unavailable; do not silently switch stores and split counters.

## 8. Delivery sequence

1. Finalize the remaining semantic contract decisions above.
2. Implement the MySQL adapter against the additive V2 table.
3. Prove atomicity, concurrency, retention, and fail-closed behavior with MySQL 8.4 Testcontainers.
4. Benchmark and approve thresholds, windows, cooldown, and outage behavior using representative performance evidence.
5. Integrate the port into the WMS login use case only after the preceding controls pass.
6. Reassess Redis only when production evidence or an existing managed service justifies it.

## 9. Verification status

The semantic application port and result-contract unit tests are implemented. It does not claim that:
- a MySQL persistence adapter exists;
- login throttling is active or integrated with credential verification;
- any numeric threshold is approved;
- a login endpoint is available;
- Redis is installed or required.

## 10. References

- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- OWASP API4:2023 — Unrestricted Resource Consumption: https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/
- MySQL 8.4 — InnoDB Locking: https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html
- MySQL 8.4 — INSERT ... ON DUPLICATE KEY UPDATE: https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html
- Redis — Programmability / Lua scripting: https://redis.io/docs/latest/develop/programmability/
