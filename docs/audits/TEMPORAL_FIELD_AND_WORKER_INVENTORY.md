# Temporal Field and Worker Inventory

**Status:** Preliminary source-backed inventory; findings are not runtime certification.
**Reviewed commit:** `37cc6a3380c58bdca0ee2607af2f84d280538a3b`
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
- `WmsOperationalReportingService` obtains `UTC_TIMESTAMP(6)` as `LocalDateTime` and explicitly converts it with `ZoneOffset.UTC` to `Instant` for `generatedAtUtc`.
- The reporting MySQL tenant-isolation integration test asserts the returned Java value is `Instant` and the serialized JSON timestamp ends in `Z`. This verifies the API representation for that tested path; it is not a global certification of every reporting timestamp or database session.

### Business schedule fields
- Inbound `expectedAt`, outbound `scheduledAt`, and inter-warehouse transfer `scheduledAt` are API/database `LocalDateTime` fields.
- The API contract currently does not specify timezone, offset, DST gap/overlap behavior, or whether these values represent tenant-local wall-clock schedules or instants. Do not change these fields to an instant representation without a compatibility decision.

## 2. Preliminary risk assessment

| ID | Area | Evidence-backed risk | Status |
|---|---|---|---|
| TEMP-01 | Business schedules | `LocalDateTime` is timezone-free; tenant-local schedule semantics and DST policy are not explicit in API v1 | Contract gap; no runtime bug confirmed |
| TEMP-02 | MySQL session timezone | Selected identity action-token, outbox, and throttle adapter tests now execute with a Connector/J `+07:00` session; this does not prove every ORM/default/SQL path is session-independent | Partially verified; broader runtime verification needed |
| TEMP-03 | ORM audit timestamps | `UserEntity`/`TenantEntity` use `LocalDateTime` with Hibernate creation/update timestamps; interaction with server/session timezone is not verified | Test gap |
| TEMP-04 | Reporting `generatedAtUtc` | Reporting service now exposes an explicit `Instant`; integration test verifies Java type and ISO-8601 `Z` serialization | Addressed for the operational summary path; other reporting paths remain in scope |
| TEMP-05 | Outbox expired action token | Expired payload is sent through retry scheduling with current attempt count; examine whether repeated immediate retries until max attempts are desirable or should be terminalized immediately | Behavior review needed; not confirmed defect |
| TEMP-06 | Worker lease | Stale PROCESSING recovery and attempt-count fencing exist; no runtime stress evidence here for timezone skew, lease expiry during SMTP, or multi-instance delivery behavior | Test/stress gap |

## 3. Recommended test scenarios before code changes

1. Run MySQL Testcontainers with session timezone deliberately set to a non-UTC value and verify identity token insert/read/expiry/consume/revoke round trips preserve the intended instant.
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
