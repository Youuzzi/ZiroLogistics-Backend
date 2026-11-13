# ZiroLogistics WMS Database Architecture

- **Document ID:** DB-01
- **Version:** 0.2
- **Status:** Proposed — architecture baseline for review
- **Scope:** New WMS database foundation
- **Database target:** MySQL 8.4 LTS
- **Schema management:** Flyway
- **Application baseline:** Java 21, Spring Boot 3.5.x, Spring Data JPA
- **Testing baseline:** MySQL 8.4 through Testcontainers

## 1. Purpose

This document establishes the architectural principles and domain boundaries for the next ZiroLogistics WMS database. It is the parent reference for the domain model, ERD, schema specification, migration plan, and database validation strategy.

The product roadmap is intentionally staged:

- **Initial target:** small and medium warehouses, with reliable receiving, storage, stock control, internal movement, and dispatch workflows.
- **Growth stage:** support more warehouses, higher transaction volumes, richer operational controls, and additional integrations when justified by actual requirements.
- **Long-term direction:** selectively expand toward larger and enterprise warehouse operations when product maturity and customer needs warrant it.

The target is **design a sound, extensible foundation; implement only what the current stage needs**. Enterprise-scale capabilities are future options, not first-release requirements or claims about current product capability.

## 2. Current-state context

The current backend database has six principal tables: `users`, `warehouses`, `items`, `bins`, `inventory_stocks`, and `inventory_ledger`.

The existing V1 migration is a useful reference for current behavior, not the final schema contract for the new database. The current V2 decimal-precision work is also a candidate input, not an automatic final decision.

The existing local database `zirologistics_db` is retained as a read-only audit/reference source during the redesign. It must not be dropped, reset, or modified as part of creating the new database.

## 3. Architectural objectives

1. **Inventory correctness:** stock balances, physical movement, reservations, and ledger history must have explicit and testable semantics.
2. **Traceability:** operational transactions must be traceable from source document through execution and resulting inventory movements.
3. **Location clarity:** warehouse, storage area, and storage location must have explicit identities and relationships.
4. **Measurement correctness:** quantities, units, conversions, and physical measurements must preserve defined precision and semantics.
5. **Transactional safety:** concurrent operations must not create negative stock, duplicate movements, or inconsistent balances.
6. **Auditability:** business events and user actions must be attributable and reviewable.
7. **Security boundaries:** organization and warehouse access must be enforceable on the backend and reflected in data ownership.
8. **Evolution:** new tracking dimensions and operational workflows should be addable without redesigning the entire inventory core.
9. **Reproducibility:** a fresh database must be constructible from versioned migrations and deterministic reference/seed data.
10. **Operational simplicity:** the initial implementation must remain practical for small and medium warehouses and must not introduce distributed services or unnecessary enterprise workflow complexity.

## 4. Proposed bounded context / domain map

The following domains are proposed. They are logical boundaries; they do not require one microservice per domain.

| Domain | Responsibility | Candidate concepts |
|---|---|---|
| Organization & Access | Ownership and authorization scope | Organization, Company, User, Role, Permission, Membership, Warehouse Assignment |
| Warehouse Structure | Physical warehouse topology | Warehouse, Site, Zone, Aisle, Rack, Bin, Location Type |
| Item Master | Product identity and handling attributes | Item, SKU, Category, Barcode, UOM, UOM Conversion, Handling Attribute |
| Inventory Core | Current inventory state and history | Stock Balance, Inventory Ledger, Inventory Status, Reservation, Allocation |
| Inbound | Receipt and storage of incoming goods | ASN/Expected Receipt, Receipt, Receipt Line, Receiving, Putaway Task |
| Internal Operations | Movement and control within the warehouse | Transfer, Movement Task, Replenishment, Stock Adjustment, Cycle Count |
| Outbound | Picking and dispatch | Outbound Order, Shipment, Shipment Line, Pick Task, Packing, Dispatch |
| Traceability | Additional identity dimensions | Lot/Batch, Serial Number, License Plate / Handling Unit, Expiry |
| Governance | Operational evidence and deduplication | Audit Event, Idempotency Record, Integration Event |

The candidate concepts are not an approved table list. The domain model must determine which concepts are required for the first operational release and which remain extension points.

## 5. Core architectural principles

### 5.1 Organization and data ownership

Business-owned records must have an explicit ownership path. The final model must decide whether the initial product is:
- one organization operating multiple warehouses;
- multiple companies under one customer/organization; or
- a multi-tenant service for independent customer organizations.

This decision affects primary/alternate keys, uniqueness scopes, foreign keys, query filters, and authorization. A generic `tenant_id` column must not be added mechanically without defining tenant lifecycle and isolation rules.

**Accepted initial direction:** the first product stage serves one organization/company that may operate multiple warehouses. Warehouse ownership and user-to-warehouse access must be explicit in the model.

The architecture should avoid choices that make later multi-company or multi-tenant expansion prohibitively difficult. However, full multi-tenant isolation, tenant provisioning, cross-tenant administration, and SaaS billing are **not** first-release requirements and must not be represented as implemented capabilities. Any later move to multi-company or multi-tenant operation requires a separate architecture decision and security review.

### 5.2 Warehouse and location model

A warehouse is a managed physical/logical facility. Storage locations must be first-class records with stable identifiers and warehouse ownership.

The final hierarchy may include site, warehouse, zone, aisle, rack, shelf, and bin, but not every level must be mandatory. The model should support configurable location types and capacity/handling attributes without requiring every warehouse to use the same physical layout.

A location code's uniqueness scope (global, per warehouse, or per parent location) must be explicitly specified.

### 5.3 Inventory state and ledger

The model must distinguish:
- physical on-hand quantity;
- available quantity;
- reserved quantity;
- quantities in non-available or quality-controlled statuses;
- quantity in transit or in an active movement, where applicable.

These are not interchangeable concepts. The schema and domain rules must define whether balances are stored, derived, or maintained as projections, and how they reconcile to immutable movement history.

Every posted inventory movement must have a durable transaction identity and traceable source. Corrections should be represented by controlled reversal/adjustment events rather than silently rewriting posted history.

The exact ledger granularity (item + location + status + lot/serial/license plate dimensions) must be decided in the inventory model.

### 5.4 UOM and measurement

Item base UOM, transaction UOM, conversion rules, and physical weight/volume are separate concepts.

Conversions must be explicit and dimensionally valid. Weight per base unit must not be confused with stock quantity. Product-specific density is required when converting volume to mass; no universal ML-to-KG conversion may be assumed.

Precision and rounding rules must be derived from supported operational units and maximum expected quantities, then tested against MySQL DECIMAL behavior. DECIMAL precision must not be selected solely by copying the current V2 proposal.

### 5.5 Item tracking

The architecture should evaluate lot/batch, serial, expiry date, and license plate/handling unit tracking.

These dimensions should be optional by item or item policy where appropriate. The first release should implement only the tracking capabilities required by confirmed workflows, while preserving a clear extension path.

### 5.6 Operational documents and tasks

Inbound, internal movement, and outbound workflows should distinguish business documents from execution tasks and inventory postings.

For example, receiving a shipment, putting stock away, reserving stock, picking, packing, and dispatching are related but distinct events. The final model must define document lifecycle, line-level status, posting boundaries, cancellation, and reversal rules.

### 5.7 Audit, idempotency, and concurrency

- Posted inventory history should be append-only by application policy and protected by database permissions/constraints where practical.
- Mutating requests that can be retried must have a defined idempotency scope and retention policy.
- Concurrency controls must protect the inventory dimensions being mutated.
- Optimistic/pessimistic locking strategy must be chosen per aggregate/workflow and verified on MySQL.
- Audit metadata and business audit events serve different purposes and should not be conflated.

## 6. Database platform and application architecture

| Concern | Proposed baseline |
|---|---|
| Database engine | MySQL 8.4 LTS |
| Storage engine | InnoDB |
| Character set | utf8mb4 |
| Schema evolution | Flyway versioned migrations |
| ORM | Hibernate / Spring Data JPA |
| Runtime architecture | Modular monolith |
| Integration tests | MySQL 8.4 Testcontainers |
| Local environment | Docker Compose or local MySQL, isolated from legacy DB |

Flyway is the authoritative schema evolution mechanism. Hibernate schema mode should validate mappings rather than silently evolve production schemas.

H2 is optional for narrow, fast tests; it is not a substitute for MySQL integration tests where MySQL-specific SQL, constraints, locking, precision, or transaction behavior matters.

## 7. Key and relational design guidelines

The detailed schema specification must decide the exact types and names. The following are design guidelines:

- Use surrogate primary keys for internal relational identity and stable public identifiers for API-facing references where justified.
- Define unique constraints according to ownership scope, not by habit.
- Use foreign keys for relational integrity unless a documented operational requirement justifies an exception.
- Index actual access paths, join predicates, uniqueness checks, and transactional locking queries.
- Avoid redundant indexes that duplicate a unique index's leading columns without measured benefit.
- Define delete behavior explicitly. Historical inventory records must not become orphaned through master-data deletion.
- Prefer explicit status/reference tables or constrained values according to the domain's change and query needs.
- Use UTC consistently for persisted instants and define business-local date/time rules separately.
- Avoid storing derived values unless there is a documented consistency/reconciliation strategy.

## 8. Fresh database and migration strategy

The new database must be created separately from the legacy `zirologistics_db`.

The initial migration sequence will be designed only after the domain model and schema specification are reviewed. A likely sequence is:
1. organization and access foundations (after ownership decisions);
2. warehouse structure;
3. item and measurement master data;
4. inventory core;
5. operational documents and execution workflows;
6. traceability extensions;
7. indexes, constraints, and supporting structures as needed.

This is a logical sequence, not an approved migration file plan. Migration files must be immutable after application to a shared environment; subsequent changes use new versioned migrations.

The initial database must be reproducible from an empty schema. Reference data and demo data must be separated from schema migrations unless a record is truly required for application correctness.

## 9. Testing and acceptance strategy

The database foundation is not accepted merely because migrations complete. At minimum, verification must cover:

1. Fresh schema creation from an empty MySQL 8.4 database.
2. Flyway history, checksums, and migration ordering.
3. Foreign keys, unique constraints, nullability, and check constraints where supported/appropriate.
4. Decimal precision, scale, overflow boundaries, and rounding behavior.
5. Inventory posting, reversal, and ledger/balance reconciliation.
6. Reservation and availability invariants.
7. Concurrent inbound, outbound, transfer, and idempotent retries.
8. Transaction rollback after failures at different workflow stages.
9. Warehouse and organization access boundaries.
10. Representative query plans and index effectiveness for high-value queries.

Tests must assert that MySQL integration tests actually ran; a skipped Testcontainers suite is not a passing database verification.

## 10. Legacy database handling

The existing `zirologistics_db` is retained as an audit/reference database during redesign.

- Do not drop, reset, baseline, or migrate it as part of this new foundation work.
- Do not copy legacy rows into the new database automatically.
- Any later data migration requires a separately reviewed mapping, cleansing, reconciliation, and rollback plan.
- Synthetic seed data for the new database must be clearly distinguished from migrated business data.

## 11. Decisions and open design questions

### 11.1 Accepted architecture decision

| ID | Decision | Accepted direction | Consequence |
|---|---|---|---|
| ADR-DB-01 | Initial ownership model | One organization/company operating multiple warehouses | Model company ownership and warehouse access explicitly. Do not implement full multi-tenant SaaS complexity in the first release. Preserve a reviewed expansion path for future multi-company or multi-tenant needs. |

### 11.2 Decisions still required

The following decisions still block final ERD and schema approval:

| ID | Decision | Options / question |
|---|---|---|
| ADR-DB-02 | Warehouse topology | Required hierarchy levels and location uniqueness scope |
| ADR-DB-03 | Inventory dimensions | Status, lot, serial, owner, license plate, and location granularity |
| ADR-DB-04 | Quantity model | Supported UOMs, conversion graph, precision, rounding |
| ADR-DB-05 | Operational scope | Which inbound, putaway, transfer, picking, packing, shipping workflows are first-release requirements |
| ADR-DB-06 | Stock reservation | Reservation lifecycle, allocation strategy, and availability semantics |
| ADR-DB-07 | Tracking policy | Which item classes require lot, serial, expiry, or license plate |
| ADR-DB-08 | Audit policy | Business event retention, reversals, and immutability controls |

No final table schema should be generated until these decisions have been recorded and reviewed.

## 12. Definition of ready for SQL implementation

SQL implementation may begin when:
- the small/medium warehouse first-release capability scope is approved;
- the ownership and warehouse hierarchy decisions are resolved;
- inventory balance, ledger, reservation, and movement invariants are explicit;
- UOM and measurement rules are approved;
- the ERD and table specification have been reviewed;
- migration and test acceptance criteria are agreed.

## 13. Related documents

Planned or existing supporting documents:
- WMS Business Domain & Capability Model
- WMS Entity Relationship Specification
- WMS Core Database Schema Specification
- Inventory & Stock Data Integrity Specification
- Warehouse Structure & Location Model
- Item, UOM & Measurement Specification
- Inbound, Outbound & Internal Movement Model
- Lot, Serial & License Plate Strategy
- Security, Organization & Data Isolation
- Database Migration & Versioning Strategy
- Database Testing & Validation Strategy

Existing UOM, occupancy, audit, and roadmap documents should be reconciled against this architecture before being treated as final specifications.

---

**Change policy:** This document is a public-facing architecture proposal for an actively developed project. It describes intended design direction, not a claim that all listed capabilities are implemented or production-certified. Changes to approved architectural decisions must be recorded with rationale, impact, and migration consequences.