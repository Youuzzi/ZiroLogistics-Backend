# WMS Password Hash Provenance and Provisioning Audit

**Status:** Historical repository audit reconciled with current identity implementation; provenance of pre-existing account hashes remains **UNVERIFIED**.  
**Original audit baseline:** `main` at `48790bc2c9c055a6337b5d445486d635a84fa76f`  
**Current code checkpoint:** `main` at `148177826c5eebd56b6134851e5d901809549448`  
**Scope:** Source and test review only. No production or user database connection or hash-value inspection was performed.

## 1. Executive result

The WMS authentication code can verify a password against a hash using the WMS Argon2id encoder. The repository does not establish that pre-existing rows in the WMS `users.password_hash` column were created by that encoder.

That finding applied only to the original audit checkpoint and is now superseded. The current WMS identity implementation includes invitation acceptance, password setup through the invitation lifecycle, authenticated password change, password reset, email verification, and single-use identity action-token persistence. The credential-authentication use case also records successful login metadata after the throttle reset commits. These capabilities do not establish the provenance of hashes already present in an existing database.

**Decision:** Keep hash provenance for pre-existing accounts marked UNVERIFIED until an authorized read-only aggregate inventory and provenance review are completed. Use the implemented WMS-only password lifecycle for newly invited or resetting accounts. Never infer compatibility from the legacy runtime.

## 2. Repository evidence

| Evidence | Finding | Status |
|---|---|---|
| WMS V1 schema | `users.password_hash VARCHAR(255) NOT NULL`; the frozen V1 migration defines storage but does not encode or identify the algorithm of any account value. | VERIFIED — schema |
| WMS identity mapping | WMS `UserEntity` maps `password_hash` to `passwordHash`. | VERIFIED — source |
| WMS password encoder | WMS-only `wmsPasswordEncoder` is Argon2id with 16-byte salt, 32-byte output, parallelism 1, memory 19 MiB, and 2 iterations. | VERIFIED — source and unit tests |
| WMS credential verification | `WmsCredentialAuthenticationService` calls `PasswordEncoder.matches(submittedPassword, user.getPasswordHash())`; malformed/unsupported runtime failures trigger dummy verification and are rejected. | VERIFIED — source and tests |
| WMS password write/provisioning path | Invitation acceptance/password setup, authenticated password change, and password reset are implemented through WMS identity services and use the WMS Argon2id encoder. | VERIFIED — current source; see current HTTP contract and MySQL identity tests |
| Legacy password path | Legacy `AuthServiceImpl.register` writes `users.password` using the legacy injected encoder; legacy `SecurityConfig` provides BCrypt. Legacy `UserEntity` maps a `password` field, not WMS `password_hash`. | VERIFIED — source |
| Demo account initializer | The demo-only initializer writes the legacy `password` field and is guarded by the `demo` profile; it is not a WMS password bootstrap. | VERIFIED — source |
| Existing WMS account hash values | No production database was queried, and the schema export is DDL rather than an account-data provenance record. | UNVERIFIED |
| Test fixture hashes | The WMS MySQL integration test creates synthetic users with `wmsPasswordEncoder.encode(...)` before exercising authentication. | VERIFIED — test source only |

Relevant source locations:

- `src/main/java/com/zirocraft/zirologistics/wms/identity/infrastructure/persistence/UserEntity.java`
- `src/main/java/com/zirocraft/zirologistics/wms/identity/infrastructure/persistence/UserRepository.java`
- `src/main/java/com/zirocraft/zirologistics/wms/identity/application/WmsUserLookupService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/security/config/WmsPasswordEncoderConfiguration.java`
- `src/main/java/com/zirocraft/zirologistics/wms/security/authentication/application/WmsCredentialAuthenticationService.java`
- `src/main/resources/db/wms-migration/V1__create_tenant_wms_schema.sql`
- `src/main/java/com/zirocraft/zirologistics/service/impl/AuthServiceImpl.java`
- `src/main/java/com/zirocraft/zirologistics/config/SecurityConfig.java`
- `src/main/java/com/zirocraft/zirologistics/config/DemoAccountInitializer.java`
- `src/test/java/com/zirocraft/zirologistics/wms/security/authentication/application/WmsCredentialAuthenticationMySqlIntegrationTest.java`

## 3. Important separation: legacy authentication is not WMS authentication

The legacy runtime and WMS runtime use different entity mappings and distinct runtime/database configuration.

- Legacy authentication uses the legacy `UserEntity.password` field and BCrypt configuration.
- WMS identity maps `users.password_hash` and has a separate Argon2id encoder.
- A BCrypt hash stored by the legacy code is not evidence that a WMS `password_hash` value is BCrypt.
- A WMS Argon2id encoder bean does not retroactively convert existing account rows.

Do not point the legacy authentication path at the WMS schema, copy legacy hashes into `password_hash`, or add an automatic BCrypt fallback without positively identifying a real WMS legacy format and approving a migration policy.

## 4. What the existing integration test proves — and does not prove

`WmsCredentialAuthenticationMySqlIntegrationTest` runs against a disposable MySQL 8.4 Testcontainers database. Its fixture creates the test user's hash using the actual WMS Argon2id encoder and then verifies successful and failed authentication flows.

This is useful evidence that the current encoder and credential orchestration interoperate on newly generated Argon2id hashes.

It does **not** prove:
- the algorithm or provenance of any existing WMS account hash;
- that all existing hashes are valid Argon2id PHC strings;
- that the deployment's email encryption, outbox delivery, and secret configuration are production-ready;
- that existing users can currently authenticate.

No user-owned database was accessed by this audit.

## 5. Required controlled provenance check

Before enabling login for existing WMS accounts, an authorized operator should run a reviewed, read-only metadata inventory in a controlled environment.

The inventory must:
1. Count rows by a safe hash-format classification (for example, recognized Argon2id prefix, separately confirmed legacy prefix, malformed/unknown, and null/empty).
2. Never print, export, commit, or log raw password hashes.
3. Avoid returning email addresses, usernames, public IDs, or other direct identifiers.
4. Record the query, execution environment, date, operator, and aggregate counts as evidence.
5. Be reviewed before any migration or login compatibility decision.

A prefix count is a format clue, not proof that a hash was generated by an approved process. If provenance cannot be established, affected accounts must use an approved password-reset/re-enrollment path rather than being accepted by guesswork.

## 6. Required WMS password lifecycle

The WMS-only password lifecycle is now implemented in application services. The following remain operational/security requirements for its use:

- invitation acceptance, password change, and password reset use WMS identity services and the qualified WMS Argon2id encoder;
- invitation, email-verification, and reset proofs use purpose-bound single-use action tokens;
- raw action tokens are carried only through the encrypted email outbox payload and are not persisted as bearer secrets;
- password reset requests use generic responses to avoid account enumeration;
- password updates increment the credential version and revoke outstanding reset tokens;
- WMS persistence and configuration remain isolated from legacy BCrypt authentication;
- 
- 

The existing `password_hash VARCHAR(255)` column is already present in frozen V1. This audit does not justify a schema alteration. Any future schema change must be additive and separately reviewed.

## 7. Adjacent login lifecycle observation

The current credential orchestration calls `recordSuccessfulLogin` after password verification and successful throttle reset, before issuing the tenant-selection token. The repository method updates `last_login_at` with `UTC_TIMESTAMP(6)` and increments the optimistic-lock version. This behavior is implemented without changing frozen V1.

## 8. Release gates

- [ ] Approved read-only aggregate inventory establishes the format distribution of existing WMS hashes.
- [ ] Unknown/malformed formats fail closed and do not gain a speculative fallback.
- [ ] If a real legacy WMS format exists, its verifier and rehash-on-success policy are explicitly approved and tested; otherwise use password reset/re-enrollment.
- [x] WMS invitation/password setup, password change, and reset workflows use the WMS Argon2id encoder.
- [ ] Argon2id benchmark and expected concurrent-login capacity are approved for target deployment hardware.
- [x] Successful login updates `last_login_at` after credential verification and throttle reset.
- [ ] Login and tenant-selection endpoints are tested with disposable MySQL 8.4 Testcontainers.
- [ ] Legacy authentication remains isolated and unchanged.

## 9. Non-goals and safety boundaries

This audit is documentation-only. It does not:

- connect to, inspect, or modify any user-owned, staging, or production database;
- print or request raw password hashes;
- change the frozen WMS V1 migration or add a migration;
- change the legacy BCrypt flow;
- independently add or enable endpoints; current WMS identity HTTP behavior is documented in the [Authentication HTTP API Contract](wms-authentication-http-api-contract.md);
- claim that existing WMS hashes are Argon2id or BCrypt;
- enable WMS authentication in a deployment.

