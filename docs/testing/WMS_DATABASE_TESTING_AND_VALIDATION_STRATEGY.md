# WMS Database Testing & Validation Strategy

- **Document ID:** DB-12
- **Version:** 0.1
- **Status:** Proposed — database verification plan
- **Parent:** DB-01 WMS Database Architecture
- **Depends on:** DB-04 Core Database Schema Specification; DB-05 Inventory Integrity; DB-11 Migration Strategy
- **Database target:** MySQL 8.4 LTS through Testcontainers

## 1. Purpose

This document defines the evidence required before the new WMS schema and database are considered ready for development use.

A successful application startup or passing unit tests alone do not prove database integrity. The validation plan must exercise real MySQL constraints, transactions, migration behavior, and concurrency-sensitive inventory operations.

## 2. Test layers

### 2.1 Schema/migration tests

Verify:
- Flyway creates the schema from an empty MySQL 8.4 database;
- migration versions and checksums are correct;
- all required tables, columns, indexes, constraints, and foreign keys exist;
- Hibernate validates the migrated schema;
- reference data is present and deterministic;
- a second application startup does not reapply migrations;
- legacy database configuration is not used by the fresh-install test.

### 2.2 Repository and mapping tests

Verify:
- JPA mappings match column types, nullability, precision, and relationships;
- unique constraints reject duplicate business keys;
- foreign keys reject orphan references;
- ownership constraints reject cross-company/cross-warehouse references;
- indexes support representative query patterns;
- optimistic version behavior works where used.

### 2.3 Service/domain integration tests

Verify business rules with actual MySQL transactions:
- valid receiving, putaway, transfer, count, adjustment, picking, and dispatch;
- invalid status transitions;
- partial execution;
- cancellation and reversal;
- idempotency;
- exact quantity precision and conversion;
- capacity and physical occupancy;
- audit actor attribution.

### 2.4 Concurrency tests

Run concurrent requests against the same stock/location and assert final persisted state.

Scenarios include:
- competing outbound requests;
- competing reservations;
- competing putaway requests for limited capacity;
- simultaneous transfer and outbound from one source balance;
- duplicate idempotent posting;
- concurrent count/adjustment where relevant.

Tests must verify the final balance, movement history, reservations, and occupancy—not just response codes.

### 2.5 API/security integration tests

Verify:
- permission enforcement;
- warehouse access scope;
- company ownership isolation;
- validation errors for invalid quantities and lifecycle transitions;
- pagination/filtering do not leak other warehouse records;
- duplicate/retry behavior is safe;
- errors do not reveal secrets or internal database details.

## 3. Test environment

- Use Testcontainers with the same major/minor MySQL version as the target database (MySQL 8.4 LTS).
- Do not rely solely on H2 or another substitute for MySQL-specific behavior.
- Each test suite should use an isolated database/schema or deterministic cleanup.
- Apply the same Flyway migrations used by the fresh installation.
- Keep test credentials and secrets ephemeral.
- Do not run destructive integration tests against the user's legacy local database.

## 4. Data and test fixture policy

Fixtures should be:
- small and deterministic;
- explicit about company, warehouse, location, item, UOM, and status;
- independent of execution order;
- realistic enough to test decimal precision and capacity;
- free of personal/customer secrets;
- created through test builders or controlled SQL setup.

Avoid fixtures that silently depend on default values not guaranteed by the schema.

## 5. Core database acceptance scenarios

### 5.1 Ownership and master data

- create one company with multiple warehouses;
- reject duplicate warehouse code within company;
- allow same warehouse code in different companies only if approved scope permits;
- reject a location linked to a warehouse from another company;
- reject duplicate location code within warehouse;
- prevent deactivation of a location with stock/open work;
- preserve historical references after master-data deactivation.

### 5.2 Inventory integrity

- unique balance dimension is enforced;
- balance and movement history commit atomically;
- no negative on-hand under default policy;
- reservation cannot exceed eligible stock;
- status-only change does not change physical quantity;
- transfer conserves stock;
- reversal is compensating and idempotent;
- reconciliation query matches balances to posted movement effects.

### 5.3 Operational workflows

- receipt posts only at approved physical custody boundary;
- putaway moves stock between valid locations;
- partial receiving and putaway preserve remaining quantities;
- count variance posts only after approval;
- picking and dispatch quantities remain distinct;
- dispatch reduces stock once;
- cancellation does not erase posted effects.

### 5.4 Precision and measurement

- each supported UOM accepts only its approved quantity scale;
- invalid fractional quantities are rejected before persistence;
- conversions preserve exact base quantity under approved rounding rules;
- unit weight and occupancy use approved precision;
- overflow and maximum operational quantity boundaries are tested;
- no movement path silently rounds stock to zero.

## 6. Reconciliation and invariant checks

Provide repeatable queries or test assertions for:
- balance versus sum of posted movement lines;
- transfer source/destination conservation;
- active reservations versus eligible on-hand;
- physical occupancy versus physically present inventory;
- source document quantities versus posted quantities;
- duplicate idempotency identities;
- orphaned or cross-owner references;
- negative or otherwise invalid balances.

Reconciliation should report discrepancies without silently changing production data.

## 7. Performance and query validation

Before a production rollout:
- capture representative query plans using EXPLAIN;
- verify composite indexes support balance and history queries;
- test pagination and warehouse-scoped list endpoints;
- assess common dashboard aggregates;
- test expected concurrent transaction volume for the initial customer segment;
- avoid premature enterprise-scale performance claims without load evidence.

## 8. Evidence and release gate

Every critical finding should be classified:
- **VERIFIED:** reproduced by test/runtime/database evidence;
- **PARTIALLY VERIFIED:** evidence covers only part of the behavior;
- **INFERRED:** derived from code/schema structure without runtime proof;
- **UNVERIFIED:** not yet tested or evidence unavailable;
- **PROPOSED:** intended design or future behavior, not implemented.

A release candidate is database-ready only when:
- migrations pass from empty MySQL 8.4;
- Hibernate schema validation passes;
- critical integrity and workflow integration tests pass;
- concurrency tests preserve invariants;
- security/ownership tests pass;
- reconciliation checks pass on controlled fixtures;
- no unresolved critical schema mismatch remains;
- test reports and migration checksums are retained as evidence.

---

**Public documentation note:** This is a proposed validation strategy. It does not claim that the new database or all tests have already been run.