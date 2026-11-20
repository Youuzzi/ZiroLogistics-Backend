# Stage 16.1 — Risk-to-Test Traceability (Inventory)

**Baseline:** `7079a36c3adfb83da2aad0ebdb4ae0e49b5abdbe`  
**Scope:** Map existing WMS test files by name to the high-risk areas defined in Stage 16.  
**Evidence status:** **INFERRED** for test-topic mapping from test names/locations only. This document does not claim the tests' assertions, execution, or completeness were inspected.

## Method and limitations

- Keep executable tests in `src/test/java`; this document is audit/traceability evidence.
- Test filenames indicate likely ownership only. Assertions, negative cases, fixtures, and runtime behavior must be inspected before calling a control VERIFIED.
- Existing tests are not expanded just to increase counts.
- Gaps below are **review candidates**, not confirmed missing test coverage.

## Traceability matrix

| Risk / control | Existing test evidence (file names) | Initial assessment / next inspection |
|---|---|---|
| Runtime boundary / WMS entrypoint | `WmsRuntimeBoundaryIntegrationTest` | INFERRED; inspect scan/package assertions and legacy boundary behavior. |
| Tenant identity persistence | `WmsTenantIdentityPersistenceIntegrationTest`, `WmsLoginMembershipRepositoryIntegrationTest` | INFERRED; inspect tenant association and cross-tenant lookup assertions. |
| Tenant lifecycle/profile/provisioning | `WmsTenantLifecycleMySqlIntegrationTest`, `WmsTenantProfileMySqlIntegrationTest`, `WmsTenantProvisioningCommandRunnerTest`, `WmsTenantProvisioningMySqlIntegrationTest`, `WmsTenantProfileControllerHttpTest` | INFERRED; inspect unauthorized tenant substitution, lifecycle constraints, rollback, and ownership. |
| Platform bootstrap | `WmsPlatformBootstrapMySqlIntegrationTest` | INFERRED; inspect single-use/atomic bootstrap, concurrent bootstrap, and fail-closed cases. |
| Role management / authorization | `WmsRoleManagementServiceTest`, `WmsRoleManagementControllerHttpTest` | INFERRED; inspect privilege escalation, tenant scoping, permission persistence/revocation. |
| Authentication / credentials | `WmsCredentialAuthenticationMySqlIntegrationTest`, `WmsCredentialAuthenticationServiceTest`, `WmsUnknownUserPasswordVerifierTest`, `WmsAuthenticationControllerHttpTest` | INFERRED; inspect inactive users, generic errors, password verification, brute-force boundaries. |
| JWT authentication/configuration | `WmsJwtAuthenticationConverterTest`, `WmsJwtTokenServiceTest`, `WmsJwtConfigurationTest`, `WmsRsaKeyMaterialTest`, `WmsHttpSecurityEnabledTest`, `WmsHttpSecurityDisabledTest`, `WmsHttpSecurityAuthEndpointsDisabledTest` | INFERRED; inspect expiry, issuer/audience, malformed tokens, key/config failure, fail-closed behavior. |
| Login throttling / client address | `WmsLoginThrottleServiceTest`, `WmsThrottleKeyHasherTest`, `WmsClientAddressResolverTest`, `MySqlWmsThrottleStorageAdapterIntegrationTest`, `WmsThrottleStorageContractTest`, `WmsV2LoginThrottleMigrationIntegrationTest` | INFERRED; inspect limits, reset windows, storage race, trusted proxy spoofing, privacy-safe keys. |
| Password hashing capacity | `WmsArgon2idBenchmarkTest`, `WmsArgon2idConcurrencyTest`, `WmsPasswordEncoderConfigurationTest` | INFERRED; benchmark tests may not be deterministic release gates; inspect their intent and environment assumptions. |
| CORS / HTTP security | `WmsCorsSecurityIntegrationTest`, `WmsApiErrorContractTest` | INFERRED; inspect preflight, exact origins, denied origins, security headers and error disclosure. |
| Identity email normalization/tokens/invitations | `WmsEmailNormalizerTest`, `WmsIdentityActionTokenGeneratorTest`, `WmsTenantInvitationServiceTest`, `WmsInvitationAcceptanceServiceTest`, `WmsInvitationAcceptanceMySqlIntegrationTest`, `WmsV3IdentityActionTokenMigrationIntegrationTest`, `WmsV5IdentityInvitationPermissionMigrationIntegrationTest` | INFERRED; inspect token expiry, replay, invalidation, tenant binding, enumeration and invitation permission. |
| Password lifecycle | `WmsPasswordManagementServiceTest`, `WmsPasswordManagementMySqlIntegrationTest` | INFERRED; inspect reset token lifecycle, reuse/replay, identity binding, credential update persistence. |
| Email outbox encryption/delivery | `WmsEmailOutboxEncryptionServiceTest`, `WmsEmailOutboxEncryptionConfigurationTest`, `WmsEmailOutboxDeliveryWorkerTest`, `MySqlWmsEmailOutboxStorageAdapterIntegrationTest`, `WmsV4IdentityEmailOutboxMigrationIntegrationTest` | INFERRED; inspect encrypted-at-rest data, lease/claim concurrency, retries, dead-letter/failure behavior, no secret leakage. |
| Warehouse master data | `WmsWarehouseManagementServiceTest`, `WmsWarehouseManagementServiceTest` (application package), `WmsWarehouseControllerHttpTest`, `WmsV10WarehouseMasterDataMigrationIntegrationTest` | INFERRED; inspect tenant ownership, validation, duplicate identifiers, capacity and HTTP semantics. |
| Inventory master data | `WmsInventoryMasterDataServiceTest`, `WmsV11InventoryCoreMigrationIntegrationTest` | INFERRED; inspect item identity, UOM, weight immutability once stock history exists, tenant boundary. |
| Inventory ledger/posting integrity | `WmsInventoryPostingServiceTest`, `WmsInventoryPostingMySqlIntegrationTest` (inventory package), `WmsInventoryPostingMySqlIntegrationTest` (application package), `WmsV12InventoryPostingMigrationIntegrationTest` | INFERRED; inspect atomicity, idempotency, negative/overflow boundaries, concurrent posting, rollback and reconciliation. |
| Inbound receiving | `WmsInboundReceivingMySqlIntegrationTest` | INFERRED; inspect receipt state transitions, duplicate posting, partial receipt, cross-tenant IDs, rollback. |
| Outbound shipment | `WmsOutboundShipmentMySqlIntegrationTest` | INFERRED; inspect reservation/availability, shipment posting, duplicate commands, invalid transitions, tenant isolation. |
| Putaway | `WmsPutawayManagementMySqlIntegrationTest` | INFERRED; inspect source/destination constraints, inventory movement consistency, capacity and race conditions. |
| Replenishment planning/concurrency | `WmsReplenishmentPlanningMySqlIntegrationTest`, `WmsReplenishmentConcurrencyMySqlIntegrationTest` | INFERRED; inspect deterministic planning, concurrent execution, locking/deadlock handling and duplicate requests. |
| Stock count | `WmsStockCountMySqlIntegrationTest` | INFERRED; inspect count lifecycle, variance handling, posting integrity, duplicate close, tenant scope. |
| Inter-warehouse transfer | `WmsInterWarehouseTransferServiceTest`, `WmsInterWarehouseTransferControllerHttpTest` | INFERRED; inspect source/destination same tenant, state machine, atomic legs, idempotency and rollback. |
| Flyway/schema contracts | `WmsV1SchemaMigrationIntegrationTest`, `WmsV2LoginThrottleMigrationIntegrationTest`, `WmsV3IdentityActionTokenMigrationIntegrationTest`, `WmsV4IdentityEmailOutboxMigrationIntegrationTest`, `WmsV5IdentityInvitationPermissionMigrationIntegrationTest`, `WmsV6PlatformBootstrapStateMigrationIntegrationTest`, `WmsV7TenantProfilePermissionMigrationIntegrationTest`, `WmsV8TenantAuditMigrationIntegrationTest`, `WmsV9RoleManagementMigrationIntegrationTest`, `WmsV10WarehouseMasterDataMigrationIntegrationTest`, `WmsV11InventoryCoreMigrationIntegrationTest`, `WmsV12InventoryPostingMigrationIntegrationTest`, `IntegerDisplayWidthMigrationPolicyTest` | INFERRED; inspect upgrade path, constraints/index/FK checks, migration rerun safety and MySQL version assumptions. |

## Initial review candidates (not confirmed gaps)

1. Cross-tenant ID substitution across controller and nested resource routes.
2. Permission revocation concurrent with an already-authorized request.
3. Bootstrap concurrent first-admin creation and partial failure recovery.
4. Invitation/reset-token replay, expiry and invalidation.
5. Inventory receipt/shipment/transfer duplicate submission and concurrent balance integrity.
6. Warehouse/location/item capacity validation under concurrent updates/postings.
7. Throttle boundaries, shared-store behavior, and trusted-proxy spoofing.
8. Migration from a clean schema and upgrade sequence on disposable MySQL 8.4.
9. Error responses must not reveal secrets, token values, stack traces, or tenant existence.
10. CORS preflight and denied-origin paths for both relevant security configurations.

## Next step

Inspect existing test assertions for P0 topics above, record precise evidence and gaps, and only then add narrowly scoped tests where a concrete risk remains. No coverage percentage or release threshold is assigned in this WP.
