# Temporal Field and Worker Inventory

**Status:** Preliminary source-backed inventory; findings are not runtime certification.
**Original reviewed commit:** `a6524a56d13f3bc4ec13c584b01dea150f53227b` (historical)
**Current code checkpoint:** `main` at `148177826c5eebd56b6134851e5d901809549448`
**Scope:** Identity expiry, outbox delivery leases/retries, login throttling, reporting timestamps, and representative WMS schedule fields.
**Compatibility:** V1–V32 are immutable. No stored-value reinterpretation or API v1 schedule-semantic change is proposed by this audit; future schema changes are additive from V33.

## 1. Evidence and implemented patterns

### Identity action tokens
- `WmsEmailVerificationService`, `WmsPasswordManagementService`, and `WmsTenantInvitationService` calculate issuance/expiry from injected `Clock` using `Instant`.
- Verification and password-reset TTLs are constants (24 hours and 30 minutes respectively); invitation TTL is configurable, defaulting to 48 hours and validated against a 5-minute to 7-day range.
- `MySqlWmsIdentityActionTokenStorageAdapter` writes expiry, created, consumed, and revoked values as UTC `LocalDateTime` with microsecond truncation. Reads convert persisted `LocalDateTime` back to `Instant` assuming UTC.
- SQL expiry/revocation checks compare UTC-converted values supplied by application code. Token consumption has a conditional update requiring `expires_at > consumedAt`; issuance requires expiry after creation.
- Migration V3 sets its migration session timezone to `+00:00`, uses `DATETIME(6)`, and stores token expiry as explicit application values. It also includes a cleanup index over expiry/consumption/revocation.

### Email outbox and worker
- `WmsEmailOutboxDeliveryWorker` is scheduled by fixed delay (default 5 seconds) and uses injected `Clock` for decisions.
- The worker recovers stale PROCESSING rows based on configured processing lease, claims one row per iteration, and verifies action-token expiry before sending.
- `MySqlWmsEmailOutboxStorageAdapter` converts instants to UTC `LocalDateTime` truncated to microseconds. Claims use `FOR UPDATE SKIP LOCKED`; claim increments `attempt_count`. Sent/retry updates are fenced by message id, PROCESSING state, and attempt count.
- Retry delay is configured with a base and maximum; retry scheduling/dead-letter status is based on persisted attempt count and max attempts. Stale lease recovery is batched.

### Login throttling
- `WmsLoginThrottleService` derives fixed windows from injected `Clock` and epoch seconds; this avoids dependence on JVM-local timezone for window boundaries.
- `MySqlWmsThrottleStorageAdapter` writes window and cooldown instants as UTC `LocalDateTime` (microsecond precision), reads cooldown timestamps back as UTC, and compares against values supplied by application code.
- Source-bucket and account-cooldown decisions are performed through transactional SQL storage. PR #295 verifies selected adapter round trips under a deliberately non-UTC MySQL session; PR #299 adds UTC session initialization to every Hikari-managed WMS connection. This remains bounded evidence, not proof for every direct SQL path.

### Reporting
- `WmsOperationalReportingService` obtains `UTC_TIMESTAMP(6)` as `LocalDateTime` and explicitly converts it with `ZoneOffset.UTC` to `Instant` for `generatedAtUtc`.
- The reporting MySQL tenant-isolation integration test asserts the returned Java value is `Instant` and the serialized JSON timestamp ends in `Z`. This verifies the API representation for that tested path; it is not a global certification of every reporting timestamp or database session.

### Business schedule fields
- Inbound `expectedAt`, outbound `scheduledAt`, and inter-warehouse transfer `scheduledAt` are API/database `LocalDateTime` fields.
- The API contract currently does not specify timezone, offset, DST gap/overlap behavior, or whether these values represent tenant-local wall-clock schedules or instants. Do not change these fields to an instant representation without a compatibility decision.

## 2. Preliminary risk assessment

| ID | Area | Evidence-backed risk | Status |
|---|---|---|---|
| TEMP-01 | Business schedules | `LocalDateTime` is timezone-free; tenant-local schedule semantics and DST policy are not explicit in API v1 | Contract gap; no runtime bug confirmed |
| TEMP-02 | MySQL session timezone | PR #299 applies `SET time_zone = '+00:00'` to Hikari-managed WMS connections; selected PR #295 adapter tests also verify round trips under a Connector/J `+07:00` session | Implemented for pooled sessions; direct SQL/ORM/default coverage remains bounded |
| TEMP-03 | ORM audit timestamps | `UserEntity`/`TenantEntity` use `LocalDateTime` with Hibernate creation/update timestamps; interaction with server/session timezone is not verified | Test gap |
| TEMP-04 | Reporting `generatedAtUtc` | Reporting service now exposes an explicit `Instant`; integration test verifies Java type and ISO-8601 `Z` serialization | Addressed for the operational summary path; other reporting paths remain in scope |
| TEMP-05 | Outbox expired action token | Expired payload is sent through retry scheduling with current attempt count; examine whether repeated immediate retries until max attempts are desirable or should be terminalized immediately | Behavior review needed; not confirmed defect |
| TEMP-06 | Worker lease | Stale PROCESSING recovery and attempt-count fencing exist; no runtime stress evidence here for timezone skew, lease expiry during SMTP, or multi-instance delivery behavior | Test/stress gap |

## 3. Recommended test scenarios before code changes

1. Extend MySQL Testcontainers coverage to remaining temporal adapters and direct SQL/ORM timestamp defaults under deliberately non-UTC sessions; selected identity/outbox/throttle round trips are already covered by PR #295.
2. Verify outbox `available_at`, `locked_at`, `sent_at`, retry scheduling, and stale lease recovery with a non-UTC session; assert microsecond precision and attempt-count fencing.
3. Verify login throttle window/cooldown boundaries at exact threshold instants and across a non-UTC session.
4. Verify `generatedAtUtc` JSON output is an explicit UTC instant, not a JVM-local presentation of a JDBC timestamp. **Implemented and tested for `WmsOperationalReportingService.operationalSummary` in PR #296.**
5. Verify ORM `@CreationTimestamp`/`@UpdateTimestamp` and DB `CURRENT_TIMESTAMP(6)` defaults against UTC contract with JVM and MySQL session timezone deliberately different.
6. Define schedule contract for tenant timezone, DST nonexistent times and repeated times, and serialization. Keep API v1 unchanged unless a versioning/compatibility plan is accepted.
7. Inventory all temporal columns, Java types, SQL timestamp expressions, scheduled jobs, and reporting filter boundaries across the full WMS codebase before treating this audit as complete.

## 4. Scope limitations

- Source review only; no live database session was opened and no runtime timezone was measured.
- This inventory is representative, not exhaustive. Additional temporal fields and SQL paths may exist in workflow services and migrations.
- No historical timestamps were changed or migrated.
- No application behavior or schema changed as part of this inventory.


## 5. Follow-up implementation and verification evidence

The preliminary findings above have been updated with the following merged changes:

- **PR #295 — non-UTC MySQL session contract tests:** identity action-token, email outbox, and login-throttle MySQL adapter integration tests establish a Connector/J session timezone of `+07:00`. The tests assert the configured session and verify an identity-token instant round trip at MySQL microsecond precision. This is targeted adapter-level evidence; it does not certify all ORM timestamp fields, SQL defaults, or every WMS workflow.
- **PR #296 — explicit JWT and reporting instants:** `WmsJwtTokenService` now uses an injected `Clock` for issuance and expiry calculations, with a UTC system Clock bean and deterministic fixed-clock tests. Operational reporting now maps the UTC database value to `Instant`; its MySQL integration test verifies the Java type and JSON `Z` suffix.
- PR #296 checks on its final head passed: Backend CI, CodeQL, and Authenticated WMS API DAST. The PR was merged as commit `37cc6a3380c58bdca0ee2607af2f84d280538a3b`.

### Remaining verification boundaries

- The above changes do not settle the tenant-local schedule contract for inbound, outbound, or inter-warehouse `LocalDateTime` fields.
- ORM `@CreationTimestamp`/`@UpdateTimestamp`, all `CURRENT_TIMESTAMP(6)` defaults, and all WMS SQL temporal expressions still require systematic review and targeted tests.
- Worker multi-instance behavior, lease expiry during slow delivery, and broader timezone-skew stress remain unverified.
- The audit remains preliminary and must not be represented as full temporal or production-runtime certification.


## 6. Expanded source inventory: database defaults and workflow timestamps

### Database and connection configuration
- `application-wms.properties` sets `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`. This is a Hibernate JDBC setting; it is not an explicit MySQL session-timezone initializer.
- The committed `compose.yml` supplies `jdbc:mysql://mysql:3306/zirologistics_wms` through `WMS_DB_URL`. It does not add a Connector/J `connectionTimeZone` / `forceConnectionTimeZoneToSession` parameter, nor does the Compose MySQL service set a server timezone.
- No source evidence in this review establishes the actual timezone of a deployed MySQL server, a pooled connection, or the JVM. Configuration absence is a verification gap, not proof that a particular deployment is currently non-UTC.

### Schema timestamp families
- The initial WMS schema migration V1 declares many `DATETIME(6)` audit and workflow columns with `DEFAULT CURRENT_TIMESTAMP(6)`; many `updated_at` columns also use `ON UPDATE CURRENT_TIMESTAMP(6)`.
- V2 (login throttle), V4 (email outbox), V6 (platform bootstrap state), V8 (tenant audit events), and later workflow migrations also introduce timestamp defaults. V19, V21, V22, and V25 add further `DATETIME(6)` columns/defaults.
- `DATETIME(6)` stores a timezone-free calendar/time value. MySQL `CURRENT_TIMESTAMP(6)` is evaluated in the connection session timezone; assigning that result to `DATETIME` stores the session wall-clock value. By contrast, explicit `UTC_TIMESTAMP(6)` asks MySQL for UTC independently of the session timezone.
- This creates a concrete consistency risk when a session is not UTC: a row written with an omitted timestamp column can receive a different wall-clock convention from a row written with explicit `UTC_TIMESTAMP(6)`. The migration defaults are not themselves a UTC guarantee.

### Runtime write-path examples
- The inbound receipt insert in `WmsInboundReceivingService` omits `created_at` and `updated_at`, relying on V1 defaults.
- The putaway task insert in `WmsPutawayManagementService` omits `created_at` and `updated_at`, relying on V1 defaults.
- The warehouse insert in `WmsWarehouseManagementService` omits `created_at` and `updated_at`, relying on V1 defaults.
- Many workflow state transitions explicitly set event and update fields using `UTC_TIMESTAMP(6)` (for example inbound receiving, putaway, picking, packing, shipping, stock count, transfers, replenishment, inventory posting, and tenant lifecycle). Those explicit paths are distinct from inserts/updates relying on schema defaults.
- `WmsPlatformBootstrapService` also explicitly writes UTC values for bootstrap completion and membership activation. `UserRepository.recordSuccessfulLogin` explicitly uses `UTC_TIMESTAMP(6)` for `last_login_at`.

### ORM-managed identity and tenancy audit fields
- `UserEntity`, `TenantEntity`, `TenantMembershipEntity`, and `RoleEntity` use `LocalDateTime` for `created_at` / `updated_at` and Hibernate `@CreationTimestamp` / `@UpdateTimestamp`.
- `UserEntity.emailVerifiedAt`, `UserEntity.lastLoginAt`, and `TenantMembershipEntity.joinedAt` are also represented as `LocalDateTime`. The reviewed invitation-acceptance path explicitly converts its injected-clock `Instant` to UTC `LocalDateTime`; successful login uses SQL `UTC_TIMESTAMP(6)`.
- The ORM-generated audit fields have not yet been tested with deliberately different JVM and MySQL session timezones. `hibernate.jdbc.time_zone=UTC` alone is not sufficient evidence that every Hibernate-generated value and database default follows the same convention.

### Business event fields and date-only values
- Workflow event columns include receipt received/posted times, shipment picked/packed/shipped times, putaway/picking/packing task lifecycle times, stock-count schedule/start/submit/review/approve/complete times, transfer request/schedule/dispatch/receive times, replenishment planned/released/start/complete/cancel/move times, adjustment request/submit/approve/reject/post times, and inventory-ledger `occurred_at`.
- Reviewed workflow write paths generally use `UTC_TIMESTAMP(6)` for event transitions. Their SQL result mapping and public API serialization still need a complete contract inventory; a UTC SQL write does not automatically make a timezone-free `LocalDateTime` response self-describing.
- Inventory lot `manufacture_date`, `received_date`, and `expiry_date` are SQL `DATE` fields. These are calendar dates, not instants; they should not be shifted through UTC or converted to a timestamp.

### Tenant-local document numbering
- `WmsReplenishmentPlanningService` and `WmsReplenishmentTaskService` build task numbers using `LocalDate.now()`, which reads the JVM default timezone rather than the tenant's stored IANA timezone.
- This does not change the stored event timestamp, but around midnight it can produce a date prefix that differs from the tenant's local business date. It is a SaaS multi-timezone consistency issue to resolve before global rollout.

## 7. Expanded risk register

| ID | Area | Evidence-backed risk | Status |
|---|---|---|---|
| TEMP-07 | Database timestamp defaults | V1 and later migrations use `CURRENT_TIMESTAMP(6)` / `ON UPDATE CURRENT_TIMESTAMP(6)` for timezone-free `DATETIME(6)`; Compose does not explicitly force UTC sessions, and representative raw inserts omit audit timestamp columns | Concrete configuration/schema risk; actual deployment impact unverified |
| TEMP-08 | ORM audit timestamps | Four JPA entities use `LocalDateTime` with Hibernate-generated creation/update timestamps; no deliberately non-UTC JVM + non-UTC session integration test covers them | Test gap |
| TEMP-09 | Tenant-local task numbering | Replenishment task numbers use JVM-default `LocalDate.now()` instead of tenant timezone | Source-confirmed timezone mismatch risk near local midnight |
| TEMP-10 | Workflow event API semantics | Many event writes use `UTC_TIMESTAMP(6)`, but `DATETIME(6)`/Java `LocalDateTime` responses do not encode UTC or tenant timezone in the value | Contract/serialization audit needed |
| TEMP-11 | Calendar dates | Lot manufacture/received/expiry values are SQL `DATE` fields | Preserve as date-only values; no UTC conversion |

## 8. Recommended remediation sequence

1. Add a MySQL Testcontainers contract test that sets a non-UTC session and exercises representative schema-default inserts and `ON UPDATE CURRENT_TIMESTAMP(6)` behavior against actual WMS tables. Include an assertion of `@@session.time_zone` and microsecond precision.
2. Decide and enforce one deployment-level UTC session policy for application JDBC connections (including externally supplied `WMS_DB_URL`), then verify it at connection startup/health diagnostics. Do not assume Hibernate's JDBC timezone setting configures the MySQL session.
3. Add integration coverage for Hibernate `@CreationTimestamp` / `@UpdateTimestamp` with JVM timezone and MySQL session timezone deliberately different. Include User, Tenant, Membership, and Role persistence paths.
4. Review every SQL insert that omits `created_at` / `updated_at` and every update relying on `ON UPDATE`. Either guarantee UTC session semantics or make the application write UTC explicitly; do not bulk-rewrite historical migrations or reinterpret existing data without a compatibility plan.
5. Resolve replenishment task-number date generation using the tenant's validated IANA timezone and a deterministic `Clock`; add tests around UTC/local midnight and daylight-saving transitions.
6. Define a versioned API policy for tenant-local schedules and timezone-free event values. Keep API v1 unchanged until compatibility behavior is agreed.
7. Add multi-instance worker tests for lease expiry during slow SMTP delivery, duplicate claim prevention, stale recovery, retry/dead-letter boundaries, and clock skew. Existing worker tests and adapter tests are not production-scale stress evidence.

## 9. Expanded scope limitations
- The repository tree at the reviewed commit contains 151 Java files under the WMS package and 28 WMS Flyway migrations. Migration timestamp declarations were scanned; Java inspection in this follow-up focused on temporal entities, persistence adapters, representative workflow services, reporting, bootstrap, and workers. It is not a claim that every Java line or every response field has been exhaustively reviewed.
- No live production database, external deployment, or local Docker environment was accessed. No migration was executed and no stored data was changed.
- This remains a source-backed audit and test plan, not production-runtime certification.
