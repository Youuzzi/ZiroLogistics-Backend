# ZiroLogistics WMS — Master Audit Report

**Document status:** Draft for review  
**Version:** 1.0  
**Prepared:** 9 November 2026  
**Repository:** `Youuzzi/ZiroLogistics-Backend`  
**Backend audit baseline:** `main` at `fdaf0a216e2069619a57c2c04ee776b7da6f1347`  
**Frontend audit baseline:** `redesign/modern-light-ui` at `88a5438c11324391d2159d5ad2483a9613fff280`  
**Runtime evidence baseline:** local backend branch `audit/inventory-quantity-precision` at `67bbbc9` (runtime evidence must not be mistaken for a run of the exact main commit).

> This report records findings and evidence. It does not authorize implementation, database changes, deployment, or merging any existing pull request.

## 1. Executive summary

The WMS has working foundations for authentication, warehouse/bin/item master data, inbound, outbound, transfer, stock queries, ledger history, low-stock alerts, and dashboard summary. The backend uses Spring Boot, Spring Security/JWT, Spring Data JPA, MySQL, and Flyway; the frontend uses React and Vite.

The highest business risk remains inventory integrity: accepted quantity precision is not aligned with the documented UOM policy, persisted bin occupancy can diverge from stock-derived physical weight, and the existing local data contains occupancy/capacity inconsistencies. The system should not be described as production-ready.

The current main branch already contains meaningful hardening (including public registration role protection, inactive-account JWT rejection, CORS integration tests, safer exception messages, and Outbound concurrency tests). These improvements are acknowledged below; earlier audit notes must not be copied forward as if those issues remain unchanged.

## 2. Evidence classification

- **VERIFIED — runtime/test/DB:** demonstrated by an executed test, local runtime request, or database query. Scope and commit must be stated.
- **VERIFIED — source/config:** directly present in the inspected source or configuration. This confirms implementation structure, not necessarily runtime behavior.
- **PARTIALLY VERIFIED:** evidence covers only part of the behavior, dataset, or environment.
- **INFERRED:** a risk inferred from code/configuration; not yet demonstrated by a failing runtime test.
- **PROPOSED:** recommended future behavior requiring product/technical approval.

## 3. Audit scope and baselines

### Backend

Inspected current `main` at `fdaf0a216e2069619a57c2c04ee776b7da6f1347`, including inventory services, request DTOs, entities, V1 migration, security configuration, global exception handling, README, and test sources.

### Frontend

Inspected `redesign/modern-light-ui` at `88a5438c11324391d2159d5ad2483a9613fff280`, including Axios configuration, route protection, sidebar, master-data pages, and Inbound/Outbound/Transfer pages. Findings against this branch should not automatically be attributed to frontend `main`.

### Runtime and database

Previously collected local evidence came from backend branch `audit/inventory-quantity-precision` at `67bbbc9`, connected to the user's local MySQL database. Those observations are explicitly identified as local evidence, not a fresh run of the current `main` commit.

## 4. Priority findings

### P0 — Inventory integrity

| ID | Finding | Evidence | Classification |
|---|---|---|---|
| INV-01 | Movement DTOs validate quantity as positive but do not enforce scale based on the item's UOM. | Inbound/Outbound/Transfer request DTOs; MySQL 8.4 Testcontainers precision test on audit branch accepted `0.004`. | Source verified; test verified on audit branch |
| INV-02 | Inbound rounds persisted occupancy to scale 2 using HALF_UP. | `InboundServiceImpl` on current main. | Source verified |
| INV-03 | Local stored bin occupancy did not match stock-derived weight. | Local SQL: A-01-01 stored 0.00 vs derived 30.00; MP-A1-01 stored 0.00 vs derived 250.00. | Runtime/DB verified for two bins and observed stock rows |
| INV-04 | Local A-01-01 capacity was 0.00 while it held stock. | Local SQL and Bin API output. | Runtime/DB verified |
| INV-05 | Physical correctness/provenance of configured item weight and bin capacity is not established. | Existing records and historical source chronology do not establish physical measurement. | Partially verified |
| INV-06 | Movement lookups do not visibly enforce active item/bin/warehouse lifecycle rules. | Service/repository source inspection. | Source verified |

**Safety note:** Do not patch local occupancy or capacity values directly. First establish physical measurement, authoritative stock scope, all-status aggregation rules, backup, and a controlled reconciliation procedure.

### P1 — Transaction safety and traceability

| ID | Finding | Evidence | Classification |
|---|---|---|---|
| TX-01 | Inbound normal-flow/capacity behavior has limited unit-test coverage. | Current `InboundServiceImplTest` focuses on duplicate request ordering. | Source/test verified |
| TX-02 | Outbound has unit coverage for normal decrement and negative occupancy rejection. | Current `OutboundServiceImplTest`. | Source/test verified |
| TX-03 | Transfer has unit coverage for both balances/ledger rows, lock ordering, idempotency, and negative source occupancy; destination capacity rejection is not covered in the inspected test. | Current `TransferServiceImplTest`. | Source/test verified |
| TX-04 | MySQL concurrency integration coverage currently exercises concurrent Outbound and duplicate Outbound idempotency, not concurrent Inbound/Transfer. | Current `MySqlInventoryLockingIntegrationTest`. | Source/test verified |
| TX-05 | Rollback after a deliberately failed later write (for example, the second Transfer ledger insert) has not been demonstrated by a dedicated MySQL integration test. | Test suite inspection. | Partially verified |
| TX-06 | Transfer is represented by two ledger rows using request ID suffixes `_OUT` and `_IN`, without a dedicated transfer identifier. | Current `TransferServiceImpl` and ledger entity. | Source verified |
| TX-07 | Ledger append-only behavior is convention-based; entity exposes setters and repository inherits general persistence operations. | Current `InventoryLedgerEntity`. | Source verified |

The current MySQL locking test uses Hibernate `ddl-auto=create` against a disposable MySQL 8.4 container. It verifies selected locking behavior but is not, by itself, proof that all constraints in Flyway V1 are present in that test schema.

### P1 — Database integrity and migration governance

| ID | Finding | Evidence | Classification |
|---|---|---|---|
| DB-01 | V1 has no CHECK constraint preventing negative stock quantity. | `V1__initial_schema.sql`. | Source verified |
| DB-02 | V1 does not constrain stock status to the supported status set. | `V1__initial_schema.sql`; entity stores status as String. | Source verified |
| DB-03 | V1 does not enforce non-null ledger movement values or the balance equation. | Ledger column definitions in V1. | Source verified |
| DB-04 | V1 has no database-level append-only protection for ledger rows. | Migration and entity/repository design. | Source verified |
| DB-05 | Transfer ledger pairing is not represented by a dedicated transfer relation/identifier. | Entity and service. | Source verified |
| DB-06 | Existing local database has no Flyway history and differs from V1 in at least `items.min_stock_level` nullability. | Local metadata/schema inspection. | Runtime/DB verified |
| DB-07 | V1 explicitly targets a fresh empty database; adoption/baseline for existing installations is separate work. | Migration comments and README. | Source verified |

V1 does provide useful protections: unique SKU, bin code, email, stock tuple `(item_id, bin_id, status)`, unique ledger request ID, and foreign keys for inventory relationships. These must be preserved in any future migration plan.

### P1 — Security and authorization

| ID | Finding | Evidence | Classification |
|---|---|---|---|
| SEC-01 | Master-data POST endpoints for warehouse/bin/item require ROLE_ADMIN; other protected routes require authentication only. | Current `SecurityConfig`. | Source verified |
| SEC-02 | Public registration is constrained to ROLE_STAFF; caller-supplied privilege escalation is covered by tests. | Current auth service and registration security tests. | Source/test verified |
| SEC-03 | Inactive accounts are rejected for JWT access; active/inactive integration coverage exists. | Current `InactiveAccountJwtSecurityIntegrationTest`. | Source/test verified |
| SEC-04 | CORS origin is hardcoded to `http://localhost:5173`. | Current `SecurityConfig` and CORS integration test. | Source/test verified |
| SEC-05 | A final production role matrix for inventory operations has not been formally documented/approved. | Security matcher policy and product decision gap. | Partially verified / decision required |

Earlier findings about public registration role escalation, inactive-account JWT acceptance, and internal exception detail leakage were addressed in the current main history and must not be listed as unresolved defects.

### P2 — API and frontend integration

| ID | Finding | Evidence | Classification |
|---|---|---|---|
| FE-01 | Axios response interceptor clears credentials and redirects to login for both 401 and 403. | Frontend redesign branch. | Source verified |
| FE-02 | Frontend ProtectedRoute checks token presence but does not enforce role-aware navigation. | Frontend redesign branch `App.jsx`. | Source verified |
| FE-03 | Warehouse, Item, and Bin creation controls are rendered without role checks. | Frontend redesign branch pages. | Source verified |
| FE-04 | Inbound and Outbound generate a new request ID inside each submit attempt, so retry after an ambiguous response may not reuse the same idempotency key. | Frontend redesign branch. | Source verified |
| FE-05 | Quantity fields use `parseFloat`; input steps are not consistently UOM-aware. | Frontend redesign branch. | Source verified |
| FE-06 | Bin occupancy percentage divides by capacity without a zero-capacity guard. | Frontend redesign branch; local A-01-01 capacity is 0.00. | Source verified; runtime data corroborates risk |
| FE-07 | New Bin form defaults capacity to 1000 without evidence that this is a physical measurement. | Frontend redesign branch. | Source verified |
| API-01 | Global exception handler has a generic 500 fallback but no explicit handler for unsupported HTTP methods. | Current main `GlobalExceptionHandler`. | Source verified |
| API-02 | HTTP 405 was observed as 500 on the earlier local audit runtime. The runtime was not a fresh execution of the exact current main SHA. | Local runtime observation plus current source inspection. | Partially verified |

### P2 — Deployment and operations

| ID | Finding | Evidence | Classification |
|---|---|---|---|
| DEP-01 | Backend Docker image runs as a non-root user. | Dockerfile. | Config verified |
| DEP-02 | Compose contains development fallback database passwords. | Compose configuration and README warning. | Config verified |
| DEP-03 | Compose publishes the MySQL host port. | Compose configuration. | Config verified |
| DEP-04 | JWT expiry is fixed at 10 hours in the inspected implementation. | JWT utility source. | Source verified |
| DEP-05 | Compose backend healthcheck is not configured. | Compose configuration. | Config verified |
| DEP-06 | CI branch push filter does not include every audit branch; pull requests targeting main still trigger the main-target workflow. | CI workflow. | Config verified |

These are deployment hardening findings, not evidence of a production deployment incident.

## 5. Runtime evidence snapshot (local environment)

The previously inspected local API returned:

- 2 warehouses, 2 items, 2 bins.
- 2 inventory stock records and 6 ledger rows.
- Stock balances matched the latest ledger balances for the two observed item/bin combinations.
- Each inspected ledger row satisfied `balance_before + quantity_change = balance_after`.
- Low-stock alert returned SKU-MU-001 with quantity 250 and minimum level 1000.
- Inbound requests with quantity 0 and -1 returned HTTP 400.
- GET Bin listing succeeded through `/api/v1.0/bins/warehouse/{warehousePublicId}`; `GET /api/v1.0/bins` is not a supported route.

This snapshot does not prove all historical transactions are present, all physical weights are correct, or all movement paths are safe.

## 6. Existing pull requests and documentation boundaries

At the time of this audit:

- [PR #3 — dashboard transaction count integrity](https://github.com/Youuzzi/ZiroLogistics-Backend/pull/3): open Draft, not merged.
- [PR #4 — UOM and quantity precision contract](https://github.com/Youuzzi/ZiroLogistics-Backend/pull/4): open Draft, not merged.
- [PR #5 — inventory occupancy integrity contract](https://github.com/Youuzzi/ZiroLogistics-Backend/pull/5): open Draft, not merged.

The UOM and occupancy domain specifications are maintained in PR #4 and PR #5 respectively. This audit report intentionally summarizes their relevance and does not duplicate those detailed domain contracts.

## 7. Overall assessment

The WMS has a credible development foundation and several meaningful safeguards. The principal release blocker is inventory correctness, especially quantity precision and reconciliation of persisted occupancy against stock-derived weight.

Recommended order:

1. Confirm physical inventory and bin capacity facts; preserve backups.
2. Approve UOM and occupancy domain rules.
3. Implement backend validation and transaction safeguards with MySQL integration evidence.
4. Approve authorization policy and align frontend behavior.
5. Plan database constraints and existing-database adoption separately.
6. Complete deployment hardening and operational verification.

## 8. Change control

This document is an audit artifact only. It does not authorize:
- production source changes;
- local or hosted database mutation;
- Flyway adoption/baseline;
- deployment or secret rotation;
- merging PR #3, #4, or #5.

Every implementation item requires a scoped change, tests, review, and explicit approval before merge.
