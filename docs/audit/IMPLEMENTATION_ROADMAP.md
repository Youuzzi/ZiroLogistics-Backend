# ZiroLogistics WMS — Implementation Roadmap

**Document status:** Draft for review  
**Version:** 1.0  
**Prepared:** 9 November 2026  
**Companion:** [MASTER_AUDIT_REPORT.md](./MASTER_AUDIT_REPORT.md)  
**Target:** Backend-first, then frontend and deployment

> This roadmap is a proposed execution sequence, not authorization to modify production data or merge changes.

## 1. Guiding rules

1. Backend remains the source of truth for inventory, authorization, and validation.
2. Preserve existing inventory data; no blind SQL correction or automatic Flyway adoption.
3. Each work package should be a small, reviewable PR with explicit acceptance criteria.
4. Use disposable MySQL/Testcontainers for destructive, rollback, precision, and concurrency tests.
5. Distinguish source-level evidence from runtime evidence.
6. Do not merge existing Draft PRs as a side effect of roadmap work.

## 2. Phase 0 — Baseline and operational safety

| Work item | Scope | Acceptance criteria | Dependency |
|---|---|---|---|
| P0-01 | Record current backend/frontend commit SHAs and PR status. | Audit references exact refs; no stale branch assumptions. | None |
| P0-02 | Secure exposed credentials and rotate affected secrets. | New secrets are generated and old credentials invalidated; no secret committed. | Owner-controlled environment access |
| P0-03 | Back up local database and verify restore procedure. | Backup exists and restore is tested in an isolated database. | Database owner |
| P0-04 | Confirm physical item weights, bin capacities, and occupancy scope. | Authoritative measurement/source and responsible approver documented. | Warehouse/business owner |
| P0-05 | Review and approve UOM and occupancy domain specifications. | PR #4 and PR #5 reviewed independently; unresolved decisions recorded. | P0-04 where physical rules apply |

**Gate:** Do not reconcile or update stored occupancy/capacity until P0-03 and P0-04 are complete.

## 3. Phase 1 — Backend inventory integrity

| Work item | Scope | Acceptance criteria | Required evidence |
|---|---|---|---|
| INV-01 | Central UOM-aware quantity policy. | PCS/BOX/GR/ML reject fractional quantities; KG accepts at most 3 fractional digits under the proposed baseline; all movement endpoints share one policy. | Unit tests for valid/invalid boundaries per UOM |
| INV-02 | Validate quantity after item resolution and before any mutation. | Null, zero, negative, and excess-scale values rejected; no stock/bin/ledger writes. | Service tests and HTTP tests |
| INV-03 | Preserve occupancy precision consistently. | No silent loss of supported quantity precision in Inbound/Outbound/Transfer. | BigDecimal boundary tests and MySQL integration tests |
| INV-04 | Define occupancy aggregation policy. | AVAILABLE, RESERVED, and DAMAGED treatment is explicit; status-only changes do not double-count physical movement. | Domain tests and reconciliation query tests |
| INV-05 | Master-data lifecycle enforcement. | Movement rejects inactive item/bin/warehouse and bin creation rejects inactive warehouse, according to approved policy. | Repository/service tests and API tests |
| INV-06 | Reconciliation command/report, initially read-only. | Reports per-bin stored vs calculated occupancy, capacity exceptions, status totals, and provenance; no automatic mutation. | Test against fixture dataset and reviewed local snapshot |

**Gate:** Any occupancy write/backfill must be a separate approved operation after read-only reconciliation and physical confirmation.

## 4. Phase 2 — Transaction safety and traceability

| Work item | Scope | Acceptance criteria | Required evidence |
|---|---|---|---|
| TX-01 | Inbound normal flow and capacity rejection tests. | Stock, occupancy, and ledger are updated together; rejected request persists nothing. | Service + MySQL integration tests |
| TX-02 | Outbound insufficient-stock and rollback tests. | Rejection leaves stock, occupancy, and ledger unchanged. | MySQL integration tests |
| TX-03 | Transfer destination-capacity and insufficient-source tests. | Rejection leaves both bins/stocks/ledger unchanged. | Service + MySQL integration tests |
| TX-04 | Transfer rollback test. | Deliberately fail second ledger insert; all stock/bin/ledger changes roll back. | MySQL integration test |
| TX-05 | Concurrency coverage for Inbound and Transfer. | Concurrent requests cannot exceed capacity, overspend source stock, or corrupt occupancy; deadlock behavior is bounded/handled. | MySQL 8.4 Testcontainers |
| TX-06 | Stable idempotency contract. | Retry of the same logical operation reuses one key; duplicate request cannot apply twice; transfer pair remains atomic. | API and concurrency integration tests |
| TX-07 | Transfer identity and ledger correlation. | A transfer can be queried as one business operation with two balanced legs. | Domain decision + migration/API tests |
| TX-08 | Ledger append-only policy. | Application correction uses approved reversal entries; ordinary API/service paths cannot edit/delete historical ledger rows. | Service/API tests and authorization tests |

## 5. Phase 3 — Database integrity and migration governance

| Work item | Scope | Acceptance criteria | Required evidence |
|---|---|---|---|
| DB-01 | Review CHECK constraints for quantity, occupancy, capacity, and ledger values. | Constraints are compatible with supported MySQL versions and approved domain rules. | MySQL 8.0/8.4 migration tests |
| DB-02 | Constrain stock status values. | Database and application status vocabulary match. | Migration and repository tests |
| DB-03 | Tighten ledger nullability and consistency. | Required movement fields cannot be null; balance equation is enforced where supported. | Migration tests and legacy data precheck |
| DB-04 | Review unique constraints and foreign-key delete behavior. | Item/bin/status uniqueness and referential integrity are preserved; delete policy is explicit. | Schema inspection and migration tests |
| DB-05 | Existing database adoption/baseline plan. | Schema diff, data prechecks, backup/restore, baseline procedure, and rollback plan are reviewed before adoption. | Isolated clone rehearsal |
| DB-06 | Transfer pair schema decision. | Explicit transfer identifier and pair semantics approved before migration. | Domain decision |

**Gate:** Never point V1 at the existing local database as-is. Migration adoption must be a separately reviewed project.

## 6. Phase 4 — Security and API behavior

| Work item | Scope | Acceptance criteria | Required evidence |
|---|---|---|---|
| SEC-01 | Approve Admin/Staff permission matrix. | Every endpoint has an explicit authentication/authority rule. | Signed-off endpoint matrix |
| SEC-02 | Enforce backend authorization. | Unauthorized role receives 403; unauthenticated/invalid session receives 401; no frontend-only security assumption. | Spring Security integration tests |
| SEC-03 | Environment-based CORS configuration. | Allowed origins are configurable; production does not depend on localhost. | CORS tests for configured and rejected origins |
| API-01 | Map unsupported methods to HTTP 405. | Unsupported method does not become generic 500. | MockMvc integration test |
| API-02 | Consistent validation/error response. | Stable error schema, safe messages, predictable status codes. | Controller/advice tests |
| API-03 | Request validation review. | Required auth and movement fields have consistent Bean Validation behavior. | HTTP integration tests |

## 7. Phase 5 — Frontend alignment

| Work item | Scope | Acceptance criteria |
|---|---|---|
| FE-01 | Handle 401 and 403 differently. | 401 clears invalid session; 403 displays forbidden feedback without forced logout. |
| FE-02 | Role-aware navigation and master-data controls. | UI reflects backend permission matrix; backend still enforces every operation. |
| FE-03 | UOM-aware quantity inputs. | Input precision/step follows approved UOM policy and retains decimal text safely. |
| FE-04 | Stable idempotency key per logical operation. | Retry reuses the same request ID until operation outcome is resolved. |
| FE-05 | Bin capacity/occupancy display. | Zero/unknown capacity is represented explicitly; no NaN/Infinity percentage. |
| FE-06 | Remove misleading capacity default. | Capacity is entered from an authoritative value or explicitly marked provisional. |
| FE-07 | User-facing transaction state. | Pending/success/failure/retry behavior does not accidentally create a new logical transaction. |

## 8. Phase 6 — Deployment and operations

| Work item | Scope | Acceptance criteria |
|---|---|---|
| DEP-01 | Remove weak Compose password fallbacks for production profile. | Production startup fails closed when required secrets are absent. |
| DEP-02 | Keep database private. | Production database is not publicly published; network access is restricted. |
| DEP-03 | Configure JWT lifecycle. | Secret strength, expiry, rotation, and revocation strategy are documented and tested. |
| DEP-04 | Health and readiness checks. | Backend and database readiness can be observed independently. |
| DEP-05 | CI coverage. | Main and active release branches receive required build/test checks. |
| DEP-06 | Backup/restore and monitoring. | Restore drill, logs, metrics, and operational alerts are documented. |

## 9. Suggested PR boundaries

Avoid one giant implementation PR. Suggested sequence:

1. `fix(inventory): enforce UOM quantity precision`
2. `fix(inventory): preserve bin occupancy precision`
3. `test(inventory): cover inbound and transfer transaction boundaries`
4. `fix(inventory): enforce active master data lifecycle`
5. `feat(inventory): add read-only occupancy reconciliation report`
6. `fix(security): align API authorization and CORS configuration`
7. `fix(api): map unsupported methods and normalize validation errors`
8. `fix(frontend): align role, retry, and UOM behavior`
9. `chore(deploy): harden secrets, networking, and health checks`

These are proposed PR titles, not created branches or implementation commitments.

## 10. Release gates

A release candidate should not be considered ready until:

- all P0 inventory integrity findings are resolved and supported by MySQL integration evidence;
- transaction rollback and concurrency tests pass;
- approved authorization matrix is enforced by backend tests;
- database migration/adoption has been rehearsed on a restored clone;
- frontend retry/idempotency and UOM handling match backend rules;
- secrets, CORS, database networking, health checks, and restore procedures are reviewed;
- open Draft PRs and unresolved domain decisions are explicitly dispositioned.

## 11. Change control

No production database correction, migration adoption, deployment, or merge is implied by this roadmap. Each work package must be reviewed and explicitly approved before merge.
