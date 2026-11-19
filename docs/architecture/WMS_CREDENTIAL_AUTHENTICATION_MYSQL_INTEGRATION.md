# WMS Credential Authentication MySQL Integration Evidence

**Status:** Integration test merged; the complete test suite passed in Main CI run #645 (352 tests, 0 failures, 0 errors, 2 skipped).  
**Scope:** Internal credential-authentication use case against disposable MySQL 8.4.  
**Database safety:** Testcontainers creates an isolated database and applies the repository's WMS Flyway migrations. No developer, staging, or production database is configured or accessed.

## What this test composes

The integration test manually composes the existing application services with real infrastructure:

- MySQL 8.4 Testcontainers with WMS V1 and additive V2 migrations;
- Hibernate schema validation (ddl-auto=validate);
- persisted global users and tenant memberships through the WMS repositories;
- the configured Argon2id password encoder;
- the real MySQL throttle storage adapter and application throttle service;
- the real credential-authentication orchestration;
- the real active-membership projection query.

The tenant-selection token service is mocked at the boundary. Token signing and decoder validation remain covered by the dedicated JWT tests; this test verifies that the orchestration invokes token issuance only after database-backed throttle reset and membership retrieval succeed.

## Scenarios

1. Successful active-user login after two below-threshold failures:
   - normalizes submitted email;
   - verifies the real Argon2id hash;
   - resets matching ACCOUNT throttle rows in MySQL;
   - preserves SOURCE throttle history;
   - returns only the active tenant membership projection;
   - invokes tenant-selection token issuance for the authenticated user's public ID.
2. Wrong password:
   - rejects with the generic credential exception;
   - persists the account failure in MySQL;
   - does not invoke token issuance.

## Deliberate boundaries

- This test deliberately composes the internal authentication use case manually and does not test HTTP adapter wiring. The credential and tenant-selection HTTP adapters were implemented later; their route/security behavior is covered by the Stage 13 controller and security contract tests.
- No deployment throttle thresholds are approved by this test; its values are test-only.
- No password hash from a real user is inspected or migrated.
- No V1 or V2 migration is modified.
- No Redis, refresh-token, logout, or session-revocation behavior is introduced.
