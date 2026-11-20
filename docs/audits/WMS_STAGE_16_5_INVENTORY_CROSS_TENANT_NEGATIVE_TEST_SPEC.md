# Stage 16.5 — Inventory Cross-Tenant Negative Test Specification

## Status

**Specification only. Runtime cross-tenant negative tests are not yet implemented or executed.**

This document defines the next test addition based on the current inventory posting integration test and source audit. It must not be treated as evidence that tenant isolation has been proven.

## Existing test foundation

The existing `WmsInventoryPostingMySqlIntegrationTest` uses Spring Boot with MySQL 8.4 Testcontainers and the WMS Flyway migration set. Its fixture helper creates tenant-scoped users, memberships, roles, permissions, warehouses, locations, and items.

The class already exercises inventory posting, duplicate request rejection, transactional rollback, audit failure rollback, concurrency, and inventory capacity behavior. These are useful foundations, but they do not by themselves prove that one tenant cannot post against another tenant's resource IDs.

## Required negative-test scenarios

### 1. Cross-tenant warehouse/location/item posting

- Create independent Tenant A and Tenant B fixtures.
- Authenticate using Tenant A's principal.
- Submit an inventory posting leg that references Tenant B's warehouse, location, and item public IDs.
- Assert the operation is rejected (capture the actual exception contract; do not hardcode a response type before confirming it).
- Assert both tenants' balances remain unchanged.
- Assert no inventory ledger rows or inventory-posting audit events were created for the rejected request.

### 2. Mixed-ownership references

Use a Tenant A warehouse/location and a Tenant B item (and, in separate cases, cross-tenant location/item combinations).

- Assert each mixed reference combination is rejected.
- Assert no partial balance, ledger, serial state, or audit changes.

### 3. Negative side-effect invariants

Capture before/after values for the rejected request:

- `inventory_balances` rows/quantity for both tenant fixtures.
- `inventory_ledger` row count for both tenants and the request ID.
- `tenant_audit_events` count for both tenants and the request ID.
- `inventory_serials` status where a serial-based test is added.

The assertions should prove rejection and atomicity, not merely inspect the exception.

## Acceptance criteria

- Tests run against disposable MySQL Testcontainers and repository WMS Flyway migrations.
- Tenant context comes from the authenticated Tenant A principal; no request-provided tenant ID is trusted.
- Tenant B public IDs are used only as adversarial resource references in the test.
- Every negative test verifies absence of persisted side effects.
- Test method names and assertion messages explicitly describe cross-tenant denial.
- Test results are obtained from CI; this specification itself is not runtime evidence.

## Implementation note

Prefer extending the existing inventory posting integration fixture/tests rather than duplicating fixture setup into a new class. The fixture is currently private to the existing test class. Keep changes focused and avoid production-code modifications unless a test exposes a defect.
