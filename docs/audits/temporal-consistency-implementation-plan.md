# Temporal Consistency Implementation Plan

**Status:** Active engineering plan; not a runtime certification.
**Original plan baseline:** `2b72fb4b7870e8312b643a2b5fb71321eab0ab45` (UTC MySQL session enforcement merged from PR #299).
**Current source checkpoint reconciled:** `main` at `148177826c5eebd56b6134851e5d901809549448`.
**Scope:** UTC persistence, tenant-local business dates, WMS schedule semantics, worker leases, and API timestamp representation.

## 1. Verified foundation

- PR #299 establishes `SET time_zone = '+00:00'` for Hikari-managed WMS MySQL connections.
- PR #299 changes seven ORM timestamp entities to database-sourced Hibernate creation/update timestamps and adds MySQL integration coverage for UTC session and timestamp behavior.
- PR #296 makes reporting `generatedAtUtc` an explicit `Instant` and verifies ISO-8601 UTC serialization.
- PR #295 verifies selected identity token, email outbox, and login-throttle adapter round trips under a deliberately non-UTC MySQL session.
- PR #300 implemented tenant-IANA-timezone-based replenishment task-number dates using an injected `Clock`; it merged and its MySQL integration verification passed. This closes that task-number date slice, not the broader WMS schedule/DST contract.

These are bounded proofs for the tested paths. They do not certify every temporal field, SQL default, API response, or worker scenario.

## 2. Remaining workstreams

### A. Complete temporal inventory
Inventory every Java temporal type, SQL temporal expression, migration default, scheduled worker, and API field across WMS modules. Record each field's semantic category:
- instant/event time;
- tenant-local wall-clock schedule;
- calendar date;
- duration/lease/timeout.

Do not infer instant semantics from `LocalDateTime` or `DATETIME(6)`.

### B. Tenant-local schedule contract
Inbound `expectedAt`, outbound `scheduledAt`, and inter-warehouse transfer `scheduledAt` are currently `LocalDateTime` values. The v1 contract does not carry an offset or timezone and does not define DST gaps/overlaps. Keep API v1 unchanged while documenting this compatibility boundary.

Before introducing a versioned schedule contract, specify:
- tenant IANA timezone source and snapshot behavior;
- nonexistent local time during a DST spring-forward gap;
- repeated local time during a DST fall-back overlap;
- how schedule edits behave if tenant timezone changes;
- JSON representation and conversion to an execution instant.

A future API contract must make the distinction explicit; it must not silently reinterpret existing stored values.

### C. Background worker temporal safety
Review outbox claim, lease expiry, retry scheduling, token expiry, and multi-instance execution. Test:
- stale lease recovery at exact boundary instants;
- fencing against stale worker completion after a newer claim;
- slow delivery whose duration approaches/exceeds the configured lease;
- retry and dead-letter transitions;
- two concurrent workers claiming the same queue;
- JVM clock fixed/skewed independently from UTC database session.

Do not increase or shorten the lease without evidence from configured SMTP timeout envelope and concurrency tests.

### D. SQL and ORM consistency
Verify that every WMS pooled MySQL connection receives the UTC session initializer. Classify `CURRENT_TIMESTAMP(6)`, `UTC_TIMESTAMP(6)`, Hibernate-generated values, and explicit application timestamps. New migrations must preserve the UTC convention without rewriting historical migrations or stored values.

### E. API timestamp contract
Inventory all returned temporal values and filters. Explicitly identify UTC instants, tenant-local schedule values, and date-only values. Do not append `Z` to a timezone-free value unless it has been proven to represent UTC and conversion is explicit.

## 3. Acceptance gates

1. Complete source inventory with paths and field names.
2. MySQL Testcontainers coverage for the relevant non-UTC session and UTC pool contract.
3. Deterministic `Clock` tests around UTC and tenant-local midnight.
4. Worker concurrency/lease tests with fencing assertions.
5. API contract documentation matches actual DTO types and JSON output.
6. No historical migration rewrite, no stored timestamp reinterpretation, and no API v1 semantic change without a compatibility decision.

## 4. Current limitations

- No production database session or deployed runtime was inspected.
- Integration tests prove only their explicit test scenarios.
- Schedule DST policy remains an unresolved contract decision.
- Worker multi-instance and slow-delivery lease behavior require dedicated stress/integration evidence.
