# WMS Login Throttle V2 Storage Schema

**Status:** Historical migration design record. The V2 persistence schema is now used by the implemented MySQL throttle adapter and credential-authentication flow; production numeric policy and operational readiness remain separate release gates.  
**Migration:** `V2__create_wms_login_throttle_buckets.sql`  
**Schema rule:** WMS V1 and its source DDL remain frozen.  
**Database safety:** Integration tests use disposable MySQL 8.4 Testcontainers only. Never run migrations or tests against `zirowms_v1` or `zirologistics_db`.

## 1. Scope of this delivery

This migration created the WMS-owned persistence table for privacy-preserving account and source buckets. The application storage port, MySQL adapter, and credential-flow integration were implemented in subsequent changes.

It did **not**, at the time of this migration, implement or enable:
- credential login or tenant-selection endpoints;
- request filtering or authentication throttling;
- a storage port, repository, or MySQL adapter;
- numeric thresholds, window duration, or cooldown duration;
- scheduled cleanup;
- Redis integration;
- any modification to V1 tables, entities, or source DDL.

The table is intentionally introduced before the service implementation so the physical schema can be reviewed and verified independently. No application code reads or writes it in this delivery.

## 2. Table contract

`wms_login_throttle_buckets` stores one row per bucket identity and UTC fixed-window start.

| Column | Type | Contract |
|---|---|---|
| `id` | BIGINT | Internal surrogate primary key |
| `bucket_type` | VARCHAR(16) | ACCOUNT or SOURCE |
| `key_version` | SMALLINT UNSIGNED | Positive HMAC key version |
| `bucket_key_hash` | BINARY(32) | Raw 32-byte HMAC-SHA-256 digest; never raw email/IP |
| `window_started_at` | DATETIME(6) | UTC start instant for the fixed window |
| `attempt_count` | INT UNSIGNED | Source requests or account failed credential outcomes, depending on bucket type |
| `blocked_until` | DATETIME(6), nullable | Account cooldown end; always NULL for SOURCE |
| `created_at`, `updated_at` | DATETIME(6) | Database-maintained audit timestamps |

The complete logical bucket key is:

`bucket_type + key_version + bucket_key_hash + window_started_at`

The unique constraint prevents duplicate rows for the same bucket/window. The hash is stored as binary rather than hexadecimal text to keep the fixed digest compact and avoid collation semantics.

## 3. Index and constraint rationale

- Unique bucket/window key supports atomic upsert implementations and prevents duplicate logical buckets.
- Account cooldown index supports lookup of an active account cooldown across window rows for the current HMAC key version.
- Cleanup index supports bounded retention scans by window start and cooldown expiry.
- CHECK constraints restrict bucket type, require a positive key version, and prohibit cooldowns on SOURCE rows.
- No foreign key is used: login throttling happens before tenant selection and the bucket identities are global account/source identifiers, not tenant-owned business records.

The table uses InnoDB and the same `utf8mb4_0900_ai_ci` table collation as the frozen WMS schema. The digest itself is BINARY and is not subject to text collation.

## 4. Time and algorithm boundary

The schema supports fixed-window counters. The window start must be calculated consistently in UTC by the future application adapter, and persisted timestamps must be compared using UTC semantics.

This migration does not select a window duration or any threshold. It also does not settle the final cooldown-extension policy. Those are security/product policy decisions and must be reviewed before a login use case can consume this table.

An account cooldown lookup must consider active rows for the same account bucket across window starts; it must not inspect only the current counter window. A future implementation must define how HMAC key rotation overlap interacts with active cooldown lookup.

## 5. Atomicity requirements for the future adapter

The schema alone does not provide concurrency safety. The future MySQL adapter must:
- use the unique key as the serialization point for same-bucket upserts;
- avoid read-then-write counter updates;
- perform the pre-verification source update and account cooldown evaluation in one bounded transaction;
- return decisions based on committed/post-update state;
- atomically increment account failure outcomes;
- define success reset/decay semantics without erasing source history;
- use bounded cleanup that never removes an unexpired cooldown;
- fail closed if required throttle persistence is unavailable.

These guarantees require MySQL 8.4 Testcontainers concurrency and failure tests before login integration.

## 6. Migration verification

The V1 migration test is explicitly pinned to Flyway target version 1 and continues to assert the frozen 63-table, 165-FK, 131-CHECK baseline.

A separate V2 integration test migrates a fresh disposable MySQL 8.4 database through V1 and V2, then verifies:
- both Flyway versions are successful;
- V1 table/FK/CHECK counts remain intact;
- V2 adds exactly one table and its expected constraints/indexes;
- invalid bucket types, non-positive key versions, and SOURCE cooldowns are rejected;
- duplicate logical bucket/window identities are rejected;
- the V2 table uses InnoDB and the frozen WMS collation.

No production or user database is accessed by these tests.

## 7. Next implementation gates

1. Review and approve the storage port's semantic contract.
2. Decide and document the exact fixed-window boundary and cooldown-extension policy.
3. Implement the MySQL adapter using bounded transactions and atomic upserts.
4. Prove same-bucket concurrency, multi-instance sharing, rollback, and outage behavior with Testcontainers.
5. Approve numeric thresholds only after workload, NAT/shared-IP, abuse-case, and deployment measurements.
6. Integrate with credential login only after the persistence and policy gates pass.

## 8. References

- [MySQL 8.4: CHECK Constraints](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)
- [MySQL 8.4: InnoDB Locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html)
- [MySQL 8.4: INSERT ... ON DUPLICATE KEY UPDATE](https://dev.mysql.com/doc/refman/8.4/en/insert-on-duplicate.html)
- [Flyway: Versioned Migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
