# Tenant Locale, Timezone, Currency, and Temporal Data Policy Audit

**Status:** Source audit baseline; LOC-01 and LOC-02 are implemented through the shared `WmsTenantLocalePolicy` used by bootstrap, provisioning, and tenant profile updates. Temporal persistence/session policy remains open.
**Reviewed commit:** `a0188c95051656f61d6040ffb550dffa2efe5a9e`
**Scope:** Tenant timezone/currency validation, WMS temporal API fields, persistence mappings, SQL timestamp generation, and quantity precision visible in representative workflows.
**Assurance:** This is a targeted source audit, not a complete database-wide temporal or financial certification.

## 1. Executive findings

1. Tenant profile update, repeatable provisioning, and first-install platform bootstrap now share `WmsTenantLocalePolicy` for region-based timezone identifiers (plus explicit `UTC`) and supported ISO-4217 currency codes. PR #290 implemented this parity and added unit/MySQL integration coverage.
2. Fixed-offset timezone values such as `+07:00` and unknown currency codes such as `ZZZ` are rejected by the shared policy. Bootstrap integration tests verify rejection occurs before user/tenant writes.
3. WMS business scheduling fields such as inbound `expectedAt`, outbound `scheduledAt`, and inter-warehouse transfer `scheduledAt` are represented as `LocalDateTime`. This type contains neither an offset nor a timezone. The API reference itself records that business-local interpretation remains unresolved.
4. The WMS schema uses MySQL `DATETIME(6)` for timestamps. Source code writes some lifecycle timestamps using `UTC_TIMESTAMP(6)`, while V1 defaults many `created_at` / `updated_at` fields with `CURRENT_TIMESTAMP(6)`. Hibernate is configured with `hibernate.jdbc.time_zone=UTC`, but that property alone does not prove every SQL session's MySQL timezone or every direct SQL timestamp expression is UTC.
5. WMS quantity inputs commonly use `DECIMAL(19,4)` / `BigDecimal` with scale 4 and `RoundingMode.UNNECESSARY`. These are inventory quantities, not monetary amounts. Tenant `default_currency` exists, but this audit did not establish a complete money model, currency-specific scale policy, FX policy, or rounding policy.
6. The repository needs one explicit temporal contract before frontend integration and before adding scheduled jobs, SLA calculations, tenant-local reporting boundaries, or financial modules.

## 2. Evidence reviewed

### Tenant validation
- `WmsTenantLocalePolicy`: canonical shared normalization for region-based timezone identifiers (plus `UTC`) and supported ISO-4217 currency codes.
- `WmsTenantProfileService`, `WmsTenantProvisioningService`, and `WmsPlatformBootstrapService`: all delegate to the shared policy; bootstrap parity is implemented by PR #290.

### Temporal API and service paths
- `WMS_V1_API_REFERENCE_STAGE_13.md`: explicitly notes that some scheduled fields use `LocalDateTime` without timezone/offset and their business-local interpretation remains unresolved.
- `WmsInboundReceivingController` / `WmsInboundReceivingService`: inbound `expectedAt` is a `LocalDateTime`; persisted as `expected_at`.
- `WmsOutboundShipmentController` / `WmsOutboundShipmentService`: outbound `scheduledAt` is a `LocalDateTime`; persisted as `scheduled_at`.
- `WmsInterWarehouseTransferController` / `WmsInterWarehouseTransferService`: transfer `scheduledAt` is a `LocalDateTime`; persisted as `scheduled_at`.
- `UserEntity` and `TenantEntity`: audit and identity timestamps include `LocalDateTime` mappings.
- `WmsOperationalReportingService`: report generation timestamp is selected with `UTC_TIMESTAMP(6)` and exposed as `generatedAtUtc`.

### Persistence and runtime
- `application-wms.properties`: Hibernate JDBC timezone is configured as UTC; its datasource URL is supplied through `WMS_DB_URL`.
- `compose.yml`: the current WMS JDBC URL is `jdbc:mysql://mysql:3306/zirologistics_wms` and does not explicitly set a MySQL connection/session timezone parameter. No MySQL server timezone override is declared in the reviewed Compose service.
- `V1__create_tenant_wms_schema.sql`: numerous temporal columns use `DATETIME(6)`; many creation/update columns default to `CURRENT_TIMESTAMP(6)`; business dates such as lot manufacture/received/expiry use SQL `DATE`.
- Operational services use explicit `UTC_TIMESTAMP(6)` for selected lifecycle timestamps, including inbound receipt completion/posting and transfer dispatch/receipt.

### Numeric precision
- Inbound, outbound, transfer, picking, packing, inventory, adjustment, and replenishment APIs use `BigDecimal` for quantities.
- Representative workflow code normalizes quantities to four decimal places and rejects excess fractional precision rather than silently rounding.
- This evidence does not establish a monetary amount policy.

## 3. Policy decisions recommended

These are recommendations for a consistent contract. They are not claims that every rule is already implemented.

### 3.1 Tenant timezone
- Persist a canonical IANA/TZDB region identifier (for example `Asia/Jakarta`) for tenant business timezone.
- Permit the explicit `UTC` identifier as a universal special case.
- Reject fixed offsets (`+07:00`), abbreviations, and ambiguous short names as tenant timezone configuration.
- Implemented by PR #290: bootstrap, provisioning, and tenant profile update use the same validator. Do not reintroduce entry-point-specific timezone semantics.
- Treat timezone as tenant business configuration, not as a replacement for UTC instant storage.

### 3.2 Instants versus tenant-local business schedules
Every temporal API/database field must be classified as one of:

1. **Instant:** a globally unambiguous point on the timeline (creation, update, approval, posting, dispatch, receipt, email delivery, audit event). Store/compare as UTC; expose an explicit UTC ISO-8601 instant with `Z` or offset.
2. **Tenant-local scheduled date-time:** a business appointment such as expected receiving or scheduled shipment/transfer. Interpret the local date-time using the tenant's configured IANA timezone. At command acceptance, resolve and persist the corresponding UTC instant as the authoritative execution/sorting value; retain the submitted local value and zone only where audit or rescheduling semantics require them.
3. **Date-only business value:** lot manufacture, receipt, and expiry dates, stock-count business dates, or similar calendar concepts. Keep as SQL `DATE` / Java `LocalDate`; do not convert to UTC midnight.

For tenant-local scheduled date-times, define behavior for daylight-saving transitions even if the initial operating tenant is in Indonesia:
- Reject nonexistent local times in a forward clock gap with a clear validation error.
- For duplicated local times in a backward overlap, require an explicit offset/choice or document a deterministic selection rule.
- Persist enough information to reproduce the original user intent if tenant timezone settings later change.

Do not treat an unqualified `LocalDateTime` as a globally comparable instant. API contracts must explicitly state whether the value is tenant-local and which timezone resolves it.

### 3.3 UTC persistence contract
- UTC is the canonical storage/comparison basis for all event instants.
- Configure and verify MySQL session timezone as UTC for every runtime connection and migration/test environment; the reviewed Compose JDBC URL has no explicit connection-timezone option, so do not rely solely on Hibernate's JDBC timezone property.
- Prefer explicit UTC timestamp expressions or application-supplied UTC instants consistently. Avoid mixing session-dependent `CURRENT_TIMESTAMP` defaults with explicit `UTC_TIMESTAMP` unless the session timezone is guaranteed and tested as UTC.
- Keep microsecond precision (`DATETIME(6)`) consistent across JDBC mappings, JSON serialization, and tests.
- Add integration tests that insert/read timestamps through Hibernate, JdbcTemplate, database defaults, and direct SQL, asserting the same UTC instant.
- Document the UTC contract for backups, migration runners, reporting, and operations.

### 3.4 Currency and money
- Validate tenant currency as an actual supported ISO-4217 code using a canonical server-side policy; a three-letter regex alone is insufficient.
- Persist currency codes in normalized uppercase form.
- Do not infer monetary scale from quantity scale. Quantity precision (currently often four decimals) and money precision are separate domain concerns.
- For each future monetary field, define amount precision, currency minor-unit handling, rounding mode, and whether fractional minor units are allowed before adding the column/API.
- Do not use binary floating-point for money. Use `BigDecimal` with explicit precision/scale and explicit rounding at the domain boundary.
- FX conversion, exchange-rate source/time, rounding, and historical currency treatment require a separate approved policy; none is assumed by this audit.
- Tenant default currency is a default display/business currency, not proof that every item, supplier, customer, or transaction must be denominated in that currency.

## 4. Findings and risk register

| ID | Finding | Risk | Required treatment |
|---|---|---|---|
| LOC-01 | Bootstrap timezone validation previously differed from profile/provisioning | Different tenant creation paths could accept different timezone identifiers | **Resolved by PR #290:** shared `WmsTenantLocalePolicy`; fixed-offset and unknown IDs rejected |
| LOC-02 | Bootstrap currency validation previously checked only three uppercase letters | Invalid currency codes could enter through first-install bootstrap | **Resolved by PR #290:** shared ISO-4217 validation; unknown codes rejected |
| TIME-01 | Scheduled WMS fields use `LocalDateTime` without a timezone contract | Ambiguous interpretation, ordering, and tenant-local display | Define tenant-local schedule semantics and API representation |
| TIME-02 | Schema combines `CURRENT_TIMESTAMP(6)` defaults with explicit `UTC_TIMESTAMP(6)` writes | Session timezone can affect timestamps produced by defaults | Enforce UTC DB sessions and test every write path |
| TIME-03 | JPA audit entities use `LocalDateTime` while JDBC workflows use SQL timestamp expressions | ORM and direct SQL paths may diverge if connection/session settings differ | Integration-test cross-path round trips |
| CUR-01 | Tenant currency exists but monetary policy is not established by this audit | Future money fields may use inconsistent precision/rounding | Approve money policy before financial implementation |
| NUM-01 | Four-decimal quantity policy is visible in WMS workflows | Risk of confusing inventory quantity precision with money precision | Keep quantity and monetary value policies separate |

## 5. Required implementation sequence

1. Approve the temporal classification and public API contract for each existing scheduled field.
2. **Completed by PR #290:** introduce one shared timezone and currency validator used by bootstrap, provisioning, and tenant profile update.
3. Inventory every temporal column and Java temporal mapping across all WMS modules, including background workers, email/action-token expiry, security throttling, audit events, and reporting.
4. Classify each field as instant, tenant-local schedule, or date-only; record the mapping in a field-level data dictionary.
5. Verify MySQL session timezone at application startup/connection initialization and in Testcontainers; add round-trip tests for Hibernate and JdbcTemplate paths.
6. Decide whether existing `LocalDateTime` schedule columns require additive companion UTC-instant columns or a compatible API transition. Do not reinterpret existing stored values or rewrite historical migrations.
7. Update API reference and examples so clients never have to guess whether a date-time is UTC or tenant-local.
8. Define money/rounding policy before adding subscription billing, invoices, pricing, valuation, or financial reports.
9. Only then implement additive migrations and source changes with backward-compatible rollout and data validation.

## 6. Explicit non-goals and safety

- No existing timestamp data was converted or rewritten.
- No Flyway migration V1–V27 was modified.
- No database, tenant, bootstrap, credential, Docker, or production operation was performed.
- No assumption was made that tenant currency implies a complete financial model.
- The legacy `zirologistics_db` and frozen reference database `zirowms_v1` remain untouched.

## 7. Current status

| Area | Status |
|---|---|
| Canonical timezone validation | Implemented: shared `WmsTenantLocalePolicy` used by bootstrap, provisioning, and profile updates |
| Canonical currency validation | Implemented: shared `WmsTenantLocalePolicy` validates supported ISO-4217 codes across all three entry points |
| Bootstrap rejection-before-write | Covered by MySQL integration tests for fixed-offset timezone and unknown currency |
| Scheduled inbound/outbound/transfer API types | Verified: LocalDateTime |
| Business-local schedule contract | Unresolved in API reference |
| Database timestamp storage | DATETIME(6) in reviewed V1 schema |
| UTC Hibernate setting | Present |
| UTC database session guarantee | Not established by this audit |
| Full temporal field inventory | Not yet complete |
| Monetary precision/rounding policy | Not established |
| Complete financial model | Not established |
