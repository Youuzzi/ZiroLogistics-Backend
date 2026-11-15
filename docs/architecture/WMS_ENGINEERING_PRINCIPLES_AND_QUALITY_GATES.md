# WMS Engineering Principles and Quality Gates

**Status:** Proposed engineering baseline (reviewed against the references below)  
**Version:** 0.1.0  
**Date:** 2026-11-16  
**Applies to:** ZiroLogistics WMS V1 backend  
**Schema authority:** Frozen `zirowms_v1` schema and its checked-in source DDL/migration

> This document is an engineering policy for this product. It distinguishes formal standards, vendor-specific product guidance, technology documentation, and ZiroLogistics architecture decisions. A vendor workflow is evidence of an established implementation pattern, not a universal WMS requirement.

## 1. Purpose and trust objective

ZiroLogistics WMS must maintain reliable, explainable, and recoverable warehouse records. The system must make it possible to establish:

- what inventory is recorded, in which warehouse/location and inventory status;
- which business event changed or reserved it;
- who or which authorized process performed the event, for which tenant;
- when the event occurred, with an unambiguous time basis;
- whether the operation was committed once, rejected, reversed, or safely retried;
- whether derived balances reconcile to the underlying movements.

No software can honestly be guaranteed to contain zero defects. The engineering objective is to prevent known classes of corruption, detect anomalies, limit impact, preserve evidence, and recover safely.

## 2. Authority and interpretation

Use this precedence when making implementation decisions:

1. **Frozen WMS V1 schema** — authoritative for existing table/column/key/constraint shape. Do not silently edit the baseline migration or mutate a user database.
2. **Applicable law, contract, and customer operating policy** — must be identified for the deployment and jurisdiction; this document does not invent regulatory obligations.
3. **Formal/open standards** — e.g. GS1 Global Traceability Standard and EPCIS/CBV when interoperable traceability is in scope; OWASP ASVS for application security verification.
4. **Official technology documentation** — Spring, MySQL, Flyway and other technologies govern how their mechanisms actually behave.
5. **Vendor WMS documentation** — SAP, Microsoft and Oracle material is used to compare real operational patterns (receiving, putaway, work, cycle count, location control, batch/serial), not copied as universal rules.
6. **ZiroLogistics Architecture Decision Records (ADRs)** — product-specific decisions where the schema, business model, or operational needs require a choice.

If a source is ambiguous, version-specific, unavailable, or conflicts with the frozen schema, record the uncertainty and raise an ADR. Do not present an inference as a standard requirement.

## 3. Non-negotiable engineering principles

### 3.1 Tenant isolation and authorization

- Derive tenant and membership context from a validated authenticated identity and server-side membership state; never trust a caller-supplied tenant identifier as authorization.
- Scope every tenant-owned read and write to the active tenant. Where the schema uses composite tenant-aware foreign keys, preserve and exercise those constraints.
- Distinguish identity, tenant membership, role, permission, and resource scope. A role is a grouping mechanism, not proof of access to every warehouse or record.
- Enforce authorization in the backend service/API boundary. UI visibility is not a security control.
- Define behavior for disabled users, revoked memberships, expired sessions/tokens, tenant switching, and stale authorization claims.
- Test both positive and negative access paths, including cross-tenant identifiers supplied directly in URLs/bodies.

**Gate:** a tenant isolation test suite demonstrates that tenant A cannot read, mutate, enumerate, or infer protected tenant B resources through any exposed WMS use case.

### 3.2 Inventory integrity and quantity semantics

- Keep on-hand, reserved, and available quantities semantically distinct. For the current baseline, available quantity is derived as on-hand minus reserved; additional status/quality/location restrictions may further limit allocatable stock.
- Define quantity precision and UOM conversion behavior explicitly. Never silently round a quantity in a way that changes the business quantity.
- Prevent invalid negative balances unless a specifically approved business policy and corresponding data model support them.
- Model inventory dimensions consistently: tenant, item, warehouse/location, status, lot/batch, serial, ownership, and other dimensions only where supported by the schema and approved product scope.
- Define how holds, quarantine, damaged, expired, blocked, and allocatable inventory affect reservation and picking.
- Do not treat a cached or denormalized balance as the only source of evidence.

**Gate:** boundary/precision tests, availability tests, UOM conversion tests, and reconciliation tests pass against MySQL 8.4 Testcontainers.

### 3.3 Inventory movement history and correction

- Every posted stock-affecting operation must have a traceable business source and actor/system identity.
- Prefer append-only movement evidence for posted inventory changes. Correct posted mistakes with compensating/reversal operations rather than silently rewriting historical movement records.
- Keep the inventory ledger distinct from the security/business audit trail: the ledger explains stock movement; the audit trail explains who performed sensitive actions and how business records changed.
- Reconcile balances to the movements that are defined to contribute to those balances. Document opening balances, adjustments, reservations, releases, and transfer-in-transit treatment.
- Never claim ledger immutability unless database permissions, application behavior, and tests actually prevent unauthorized updates/deletes.

**Gate:** tests prove posted movement records cannot be edited/deleted through application paths, corrections preserve history, and balance reconciliation detects intentionally introduced discrepancies.

### 3.4 Transaction lifecycle and atomicity

- Each document/workflow has an explicit state machine, allowed transitions, transition actor, preconditions, and terminal-state rules.
- Separate draft/editable work from posted/committed inventory effects.
- Define approval, partial receipt, short/over receipt, cancellation, reversal, close, and reopen behavior per workflow; do not assume all documents share one lifecycle.
- All database changes that represent one logical operation must commit or roll back together.
- External effects (messages, email, integrations) must not be assumed atomic with a database transaction. Use an outbox or another explicitly designed delivery mechanism when reliable post-commit publication is required.
- A failed or rejected operation must not leave partial stock, ledger, document, or reservation changes.

**Gate:** tests cover every allowed transition, forbidden transition, rollback point, partial-operation case, and post-commit side-effect retry.

### 3.5 Concurrency, idempotency, and retry safety

- Identify the aggregate/resource being protected for each concurrent operation (e.g. inventory balance, reservation, task, receipt line).
- Use database constraints, conditional updates, optimistic/pessimistic locking, or a combination selected for the invariant. Do not assume application-side read-then-write validation is race-safe.
- Use a stable idempotency key/request identity for operations where clients or integrations may retry. Define key scope, uniqueness, retention, request-payload conflict behavior, and replay response.
- A duplicate request must not create duplicate stock movement, receipt, shipment, or reservation effects.
- Keep transactions short, acquire locks in a consistent order, index locking predicates, and handle deadlock rollback/retry safely.
- Retry only operations known to be safe to retry; use bounded attempts and backoff. Do not blindly retry a partially completed external side effect.

**Gate:** parallel MySQL integration tests prove no oversell/over-reserve, no lost update, no duplicate posting, and deterministic conflict/retry behavior.

### 3.6 Warehouse location and execution control

- Represent physical location hierarchy and location attributes according to the frozen schema.
- Validate that a location belongs to the selected warehouse and tenant.
- Respect location status, capacity, permitted item/storage characteristics, and inventory status where these controls are modeled and enabled.
- Keep warehouse task creation, assignment, execution, confirmation, and cancellation rules explicit.
- Do not assume every warehouse uses the same receiving, putaway, replenishment, picking, or packing strategy; make operational policies configurable only where the product model supports them.
- Scanning/barcode/RFID is an input channel, not a substitute for server-side validation.

**Gate:** tests cover invalid location/warehouse combinations, disabled or blocked locations, capacity rules where implemented, task state transitions, and duplicate scan/request behavior.

### 3.7 Traceability and time

- Decide whether traceability is item-, lot/batch-, serial-, logistic-unit-, or event-level for each supported product/workflow.
- For traceability events, capture sufficient who/what/when/where/why context. GS1 Global Traceability Standard R23 identifies event date/time (including time zone and UTC offset), traceable object ID, location ID, business process step/disposition, and responsible party as minimum event data for the applicable CTEs.
- Use GS1 identifiers and EPCIS/CBV interoperability only where the relevant trading-partner or customer use case requires it; do not label an internal event table “EPCIS compliant” without conformance work.
- Store timestamps consistently and define the business timezone, warehouse timezone, UTC conversion, daylight-saving handling, and date-cutoff rules.
- Expiry, FEFO/FIFO, and recall workflows must be explicit product policies, not accidental sort order.

**Gate:** traceability can follow a supported lot/serial from receipt through internal movements to shipment/consumption, with event times and locations unambiguous.

### 3.8 Auditability and accountability

- Record sensitive business actions: authentication/security events, membership/role/permission changes, approvals, postings, reversals, adjustments, stock count resolutions, configuration changes, and privileged access.
- Capture actor membership/service identity, tenant, action, resource type/ID, timestamp, request/correlation ID, outcome, and reason where required. Before/after values should be restricted to relevant fields and handled as sensitive data.
- Protect audit records from ordinary application-user modification. Define retention, access, export, and privacy policy.
- Never log passwords, access tokens, secrets, or unnecessary personal data.
- Audit logging must not silently fail for actions whose audit evidence is a business control. Decide whether audit persistence is in the same transaction or through a reliable outbox based on the required guarantee.

**Gate:** tests assert audit events for both successful and rejected privileged operations and verify that secrets are not emitted.

### 3.9 Reconciliation, failure handling, and recovery

- Provide repeatable checks for inventory balance vs movement evidence, reservation totals, transfer in-transit quantities, and document-to-stock consistency.
- Make reconciliation read-only by default. Corrections must be explicit, authorized, reasoned, and audited.
- Classify errors as validation, authorization, conflict, transient infrastructure, or unexpected failure; return stable safe API errors without exposing internals.
- Define retry, dead-letter/manual review, and compensation behavior for asynchronous integrations.
- Establish backup, restore, retention, and recovery objectives before production; test restores, not merely backup creation.
- A health endpoint is not proof that business processing is healthy. Monitor DB availability, queue/outbox lag, failed jobs, deadlocks, reconciliation exceptions, and transaction rates.

**Gate:** failure-injection tests demonstrate rollback/no partial commit; recovery runbooks are tested in a disposable environment; reconciliation detects seeded inconsistencies.

### 3.10 Security and privacy

- Use OWASP ASVS as a verification checklist and select a documented target level based on deployment risk; use OWASP API Security Top 10 to structure API threat reviews and the OWASP Logging Cheat Sheet to shape safe application/security logging. Do not claim ASVS compliance from a partial checklist.
- Validate input at boundaries, enforce object-level authorization, protect secrets, configure CORS narrowly, and apply rate limits to sensitive/authentication endpoints.
- Use secure password hashing and a reviewed token/session lifecycle. Define refresh/revocation and membership disablement semantics.
- Protect logs, exports, attachments, and integration credentials as data stores with access controls.
- Run dependency and static/security checks in CI where practical; remediate findings based on severity and exploitability.

**Gate:** security tests and a documented ASVS mapping cover authentication, authorization, input handling, session/token management, error handling, logging, and data protection.

## 4. Required testing strategy

Use the real database engine for behavior that depends on MySQL semantics. H2 or mocks may be used for fast unit tests, but cannot replace MySQL integration tests for locking, isolation, constraints, migration behavior, and precision.

| Test layer | Required evidence |
|---|---|
| Unit/domain | State transitions, quantity arithmetic, policy decisions, validation |
| Repository/MySQL integration | Mappings, tenant-aware queries, constraints, indexes, precision |
| Transaction integration | Commit/rollback, atomicity, audit/ledger coupling |
| Concurrency | Locking, races, deadlock handling, no lost updates |
| Idempotency | Duplicate/replay, key conflict, retry after timeout |
| Security | Tenant isolation, object-level authorization, revoked membership |
| Migration | Flyway validation and disposable-database migration from empty baseline |
| Reconciliation | Derived balances and document/ledger consistency |
| Resilience | Restart/retry/failure injection, outbox delivery if implemented |
| Contract/API | Stable request/response/error semantics and pagination limits |

All integration tests must use disposable Testcontainers databases. Never run schema-changing migrations or test cleanup against the user's local `zirowms_v1` or legacy `zirologistics_db`.

## 5. Module Definition of Done

A module is not complete merely because its endpoints return HTTP 2xx. It is eligible for completion only when:

1. Its business scope, actor permissions, resource scope, and state machine are documented.
2. Every persisted field and relationship maps to the frozen schema; no unapproved schema drift exists.
3. Tenant scope is enforced on all relevant reads and writes.
4. Transaction boundaries and failure behavior are explicit.
5. Concurrency and idempotency risks have been evaluated and tested where applicable.
6. Audit and inventory evidence are produced for relevant actions.
7. Unit and MySQL integration tests pass; security tests cover negative paths.
8. API errors, validation, pagination, and observability are consistent.
9. Documentation and ADRs reflect actual behavior, not intended future behavior.
10. CI passes on the exact commit proposed for merge.

Use evidence labels in reviews:
- **VERIFIED** — supported by executed test/runtime evidence.
- **PARTIALLY VERIFIED** — some but not all relevant scenarios were exercised.
- **INFERRED** — derived from code/schema structure only.
- **UNVERIFIED** — not yet tested or observed.
- **PROPOSED** — a design recommendation not yet implemented.

## 6. Product implementation sequence

The following is a sequencing recommendation, not a claim that these capabilities are already implemented:

1. Runtime boundary and governance.
2. Tenant, identity, membership, authentication context.
3. Roles, permissions, resource scope, authorization tests.
4. Warehouse and hierarchical locations.
5. Item catalog and UOM/conversions.
6. Inventory balance, reservations, immutable movement history, reconciliation.
7. Inbound, receiving, putaway.
8. Transfers, adjustments, stock count.
9. Lot/serial/expiry and traceability capabilities as required by target markets.
10. Replenishment, picking, packing, outbound/shipping.
11. Integrations, reporting, operational monitoring, recovery hardening.

Do not generate all JPA entities in one batch. Implement vertical slices with explicit schema mapping and evidence.

## 7. Source references

See [WMS_ENGINEERING_REFERENCES.md](WMS_ENGINEERING_REFERENCES.md) for source URLs, scope, version notes, and how each reference is used.

## 8. Change control

Changes to these principles require a pull request and, when they alter product behavior or invariants, an Architecture Decision Record. A source update does not automatically change product policy: assess its applicability, version, and impact first.

