# Temporal Field and Worker Inventory

**Status:** Preliminary source-backed inventory; findings are not runtime certification.
**Reviewed commit:** `b3ac39828880eaae967b005d9169f6e9a3801bec`
**Scope:** Identity expiry, outbox delivery leases/retries, login throttling, reporting timestamps, and representative WMS schedule fields.
**Compatibility:** No V1–V28 migration changes, stored-value reinterpretation, or API v1 changes are proposed by this audit.

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
- Source-bucket and account-cooldown decisions are performed through transactional SQL storage. This audit does not yet prove behavior under a MySQL session configured to a non-UTC timezone.

### Reporting
- `WmsOperationalReportingService` returns `generatedAtUtc` using `SELECT UTC_TIMESTAMP(6)`. This SQL expression explicitly asks MySQL for UTC, but the JDBC `Timestamp` mapping and JSON serialization path still need a round-trip test with a non-UTC JVM/session combination before we certify the response as an unambiguous instant.

### Business schedule fields
- Inbound `expectedAt`, outbound `scheduledAt`, and inter-warehouse transfer `scheduledAt` are API/database `LocalDateTime` fields.
- The API contract currently does not specify timezone, offset, DST gap/overlap behavior, or whether these values represent tenant-local wall-clock schedules or instants. Do not change these fields to an instant representation without a compatibility decision.

## 2. Preliminary risk assessment

| ID | Area | Evidence-backed risk | Status |
|---|---|---|---|
| TEMP-01 | Business schedules | `LocalDateTime` is timezone-free; tenant-local schedule semantics and DST policy are not explicit in API v1 | Contract gap; no runtime bug confirmed |
| TEMP-02 | MySQL session timezone | App-side UTC conversions are explicit in token/outbox/throttle adapters, but session configuration and DB defaults are not globally enforced/verified | Runtime verification needed |
| TEMP-03 | ORM audit timestamps | `UserEntity`/`TenantEntity` use `LocalDateTime` with Hibernate creation/update timestamps; interaction with server/session timezone is not verified | Test gap |
| TEMP-04 | Reporting `generatedAtUtc` | SQL uses `UTC_TIMESTAMP(6)`; JDBC Timestamp serialization must be verified under non-UTC timezone | Test gap |
| TEMP-05 | Outbox expired action token | Expired payload is sent through retry scheduling with current attempt count; examine whether repeated immediate retries until max attempts are desirable or should be terminalized immediately | Behavior review needed; not confirmed defect |
| TEMP-06 | Worker lease | Stale PROCESSING recovery and attempt-count fencing exist; no runtime stress evidence here for timezone skew, lease expiry during SMTP, or multi-instance delivery behavior | Test/stress gap |

## 3. Recommended test scenarios before code changes

1. Run MySQL Testcontainers with session timezone deliberately set to a non-UTC value and verify identity token insert/read/expiry/consume/revoke round trips preserve the intended instant.
2. Verify outbox `available_at`, `locked_at`, `sent_at`, retry scheduling, and stale lease recovery with a non-UTC session; assert microsecond precision and attempt-count fencing.
3. Verify login throttle window/cooldown boundaries at exact threshold instants and across a non-UTC session.
4. Verify `generatedAtUtc` JSON output is an explicit UTC instant, not a JVM-local presentation of a JDBC timestamp.
5. Verify ORM `@CreationTimestamp`/`@UpdateTimestamp` and DB `CURRENT_TIMESTAMP(6)` defaults against UTC contract with JVM and MySQL session timezone deliberately different.
6. Define schedule contract for tenant timezone, DST nonexistent times and repeated times, and serialization. Keep API v1 unchanged unless a versioning/compatibility plan is accepted.
7. Inventory all temporal columns, Java types, SQL timestamp expressions, scheduled jobs, and reporting filter boundaries across the full WMS codebase before treating this audit as complete.

## 4. Scope limitations

- Source review only; no live database session was opened and no runtime timezone was measured.
- This inventory is representative, not exhaustive. Additional temporal fields and SQL paths may exist in workflow services and migrations.
- No historical timestamps were changed or migrated.
- No application behavior or schema changed as part of this inventory.
