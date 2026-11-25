# Identity Temporal Lifecycle Audit

**Status:** Targeted source and test audit; no temporal correctness certification.
**Reviewed commit:** `b3ac39828880eaae967b005d9169f6e9a3801bec`
**Scope:** Email verification, password reset, invitations, token storage, email outbox delivery/retry/lease, JWT issuance, and login throttling.
**Method:** Repository source, migration SQL, and named unit/MySQL integration test paths. No runtime database/session inspection or external clock synchronization was performed.

## 1. Findings summary

| Area | Evidence | Assessment |
|---|---|---|
| Identity action token expiry | Token model uses `Instant`; expiry predicate is strict `expiresAt.isAfter(instant)`. | Good domain-level instant semantics and boundary rule. |
| Token persistence | MySQL adapter maps `Instant` to/from `LocalDateTime` using explicit UTC and microsecond truncation. | Consistent UTC convention in adapter; depends on all stored DATETIME values following that convention. |
| Email verification | 24-hour TTL; service uses injected `Clock`; token issue/consume/revoke and user activation are transactional with lock order. | Positive evidence; testable time source. |
| Password reset | 30-minute TTL; injected `Clock`; reset/change flows use transactional token operations and revoke outstanding reset tokens. | Positive evidence; exact expiry and concurrent paths have test coverage paths. |
| Tenant invitation | TTL supplied through configuration; service uses injected `Clock`; token issuance and revocation are persisted in UTC adapter. | Positive evidence; configuration and DB time contract still require end-to-end verification. |
| Email outbox | `available_at`, `locked_at`, `sent_at` are `DATETIME(6)`; adapter explicitly converts UTC and truncates to micros. Claim, retry, stale lease recovery, and completion use conditional writes and attempt-count fencing. | Strong implementation evidence for concurrency/state transitions; UTC wall-clock convention is implicit at schema/session boundary. |
| Outbox worker | Uses injected `Clock`; recovers expired processing leases, claims one message at a time, checks token expiry before delivery, marks sent, and schedules retry. | Positive evidence; operational clock skew across replicas and DB session timezone remain unverified. |
| JWT expiry | JWT timestamps use `Instant.now()` and TTL additions. | Correct instant type, but direct system clock is not injected; deterministic tests/time skew controls are less consistent than services using `Clock`. Not evidence of a production defect by itself. |
| Login throttle | Uses injected `Clock`, epoch-aligned fixed windows, and instant-based cooldown decisions; adapter persists UTC `LocalDateTime` values. | Positive evidence; DB session/runtime timezone and replica clock skew are not certified. |

## 2. Source evidence

- `WmsIdentityActionTokenRecord.isUsableAt(Instant)` rejects consumed/revoked tokens and requires `expiresAt > instant`; equality at expiry is expired.
- `WmsEmailVerificationService` sets a 24-hour expiry from injected clock, stores UTC conversion for `emailVerifiedAt`, and commits user activation plus token consumption/revocation in one transaction.
- `WmsPasswordManagementService` sets a 30-minute reset TTL, uses injected clock, locks the user before token rows, and revokes outstanding reset tokens when credentials change/reset.
- `WmsTenantInvitationService` uses injected clock and configured TTL.
- `WmsInvitationAcceptanceService` checks usability after locks using its clock and converts joined/verified timestamps to UTC wall-clock `LocalDateTime`.
- `MySqlWmsIdentityActionTokenStorageAdapter` converts instants to UTC `LocalDateTime` truncated to microseconds before SQL writes and converts retrieved `LocalDateTime` back to UTC `Instant`.
- `MySqlWmsEmailOutboxStorageAdapter` uses the same explicit UTC/microsecond conversion for schedule/lease/send fields. Claims are ordered by availability and ID; state updates include attempt-count fencing; stale PROCESSING leases can be recovered.
- `WmsEmailOutboxDeliveryWorker` uses `Clock`, checks token expiration before sending, and schedules retry using the clock plus configured delay.
- `WmsLoginThrottleService` uses injected `Clock` for source windows, account failure windows and cooldown; the MySQL adapter uses UTC `LocalDateTime` mapping.

## 3. Tests identified

Existing test source paths include:

- `WmsIdentityActionTokenGeneratorTest`
- `WmsInvitationAcceptanceServiceTest`
- `WmsInvitationAcceptanceMySqlIntegrationTest`
- `WmsPasswordManagementServiceTest`
- `WmsPasswordManagementMySqlIntegrationTest`
- `WmsTenantInvitationServiceTest`
- `WmsEmailOutboxDeliveryWorkerTest`
- `MySqlWmsEmailOutboxStorageAdapterIntegrationTest`
- `MySqlWmsIdentityActionTokenStorageAdapterIntegrationTest`
- `WmsLoginThrottleServiceTest`
- `MySqlWmsThrottleStorageAdapterIntegrationTest`

Path inventory is not a claim that every boundary, DST condition, session timezone, multi-node clock skew, or retry race has been verified by test assertions. Those test bodies need a follow-up focused review.

## 4. Risks / follow-up

### IDT-01 — UTC convention depends on persisted wall-clock contract
Adapters explicitly map instants to UTC `LocalDateTime` and back. Since these fields are `DATETIME(6)`, they do not intrinsically carry a timezone. Runtime MySQL session timezone and timestamp-default behavior must be tested and/or explicitly enforced before declaring the full persistence contract certified.

### IDT-02 — Outbox leases depend on application clocks
Lease cutoff, claim timestamps, retry availability, and token expiry use application `Clock`. Multiple application replicas with clock skew could recover/claim leases at different perceived times. Confirm deployment clock synchronization, document tolerated skew, and consider DB-time coordination only if requirements justify it.

### IDT-03 — JWT service uses direct system clock
`WmsJwtTokenService` uses `Instant.now()` instead of injected `Clock`, unlike several identity/throttle services. This is primarily testability/clock-control inconsistency. A compatible follow-up can inject `Clock` and add boundary tests; no API claim or JWT format change is needed.

### IDT-04 — Audit/default SQL timestamp consistency
The schema includes `CURRENT_TIMESTAMP(6)` defaults and some workflows use explicit `UTC_TIMESTAMP(6)`. The values are consistent only when the session/database timezone contract is controlled or each write path converts correctly. Inventory-wide SQL timestamp generation remains outside this targeted identity review.

### IDT-05 — Test inventory does not equal test coverage
The listed tests should be inspected for exact expiration boundary assertions, stale lease fencing, retry transitions, micros precision, session timezone variation, and concurrency scenarios. Follow-up work will classify assertions by evidence rather than infer coverage from filenames.

## 5. Recommended implementation sequence

1. Keep public API/token format and schema stable.
2. Inject `Clock` into JWT token service with a production UTC/system clock default and deterministic unit tests.
3. Review outbox/token/throttle integration tests for exact boundaries, UTC round-trip, micros precision, stale lease recovery and attempt-count fencing; add missing tests.
4. Add a disposable MySQL Testcontainers timezone/session test that deliberately uses a non-UTC session and verifies explicit UTC mapping paths, defaults, and SQL expressions. Do not connect to or modify any user database.
5. Broaden audit to operational WMS schedule fields, inventory events, stock count/replenishment, reporting boundaries, and remaining migration timestamp defaults.

## 6. Safety

No migration, existing data, API v1 field representation, production DB, tenant record, credential, or Docker resource was changed. Findings are source-based and remain limited to this scope.
