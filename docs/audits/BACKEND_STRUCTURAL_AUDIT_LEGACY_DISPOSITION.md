# Backend Structural Audit — Legacy Disposition

**Baseline:** `59728ad97cb1a2091a45f76df8b39962a622f188` (PR #160 and #161 merged; Main CI run `44643783141` successful)  
**Status:** Legacy cleanup is applied on `refactor/retire-legacy-logistics-runtime-v2`; final combined CI and diff review are pending. The original PR #159 remains Draft and is superseded by this rebased cleanup branch.

## 1. Audit method and evidence labels

This audit distinguishes:

- **VERIFIED:** directly confirmed from source, configuration, or test/runtime evidence.
- **PARTIALLY VERIFIED:** a WMS replacement exists, but exact legacy behavior parity is not yet proven.
- **MOVE / REIMPLEMENT:** business behavior remains relevant but must be implemented with WMS models and boundaries; do not copy legacy classes.
- **REMOVE:** legacy-only runtime or contract with no intended WMS role.
- **PROPOSED:** cleanup action that still requires final CI and diff review.

Package exclusion alone is not proof that a class is useless. Each disposition below also considers its dependencies, data model, WMS equivalent and test coverage.

## 2. Runtime boundary

**VERIFIED**

- `WmsRuntimeApplication` scopes component scan, entity scan and Spring Data repository scan to `com.zirocraft.zirologistics.wms`.
- The WMS executable entry point is `com.zirocraft.zirologistics.wms.WmsRuntimeApplication`.
- CI builds with `./mvnw --batch-mode --no-transfer-progress -Pwms-runtime clean verify`.
- `application-wms.properties` points Flyway only to `classpath:db/wms-migration`; Flyway remains opt-in.
- WMS migrations V1–V26 are frozen and must not be changed during this cleanup.
- PR #158 moved the 13 WMS migration contract tests into the WMS namespace; they remain retained.

## 3. Production source disposition — 63 legacy classes

These classes belong to the old single-tenant Logistics application. Their implementations are not copied wholesale into WMS. The WMS modules below are the intended replacements.

| Legacy group / classes | Count | WMS disposition |
|---|---:|---|
| `ZirologisticsApplication` | 1 | **REMOVE.** WMS runtime has its own application entry point. |
| Config: `AuditConfig`, `DemoAccountInitializer`, `SecurityConfig`, `WebConfig` | 4 | **REMOVE.** WMS has explicit tenant audit, platform bootstrap/invitation, JWT security and WMS CORS. The old custom `X-Powered-By` header is not retained. WMS CORS migration is implemented in PR #160 and merged. |
| Controllers: `AuthController`, `BinController`, `DashboardController`, `InboundController`, `InventoryController`, `ItemController`, `OutboundController`, `TransferController`, `WarehouseController` | 9 | **REMOVE.** Replaced by versioned WMS HTTP controllers for authentication/invitations, warehouse/location, operational reporting, inbound, inventory, item master, outbound fulfilment and inter-warehouse transfer. |
| Entities: `BaseEntity`, `BinEntity`, `InventoryLedgerEntity`, `InventoryStockEntity`, `ItemEntity`, `UserEntity`, `WarehouseEntity` | 7 | **REMOVE.** These map the old schema and single-tenant identity model. WMS uses tenant-scoped schema tables, explicit audit events, inventory balances/ledger, item master and warehouse locations. |
| Exceptions: `ApiException`, `GlobalExceptionHandler` | 2 | **REMOVE.** WMS owns its stable error contract and authentication exception advice. The WMS error-contract test no longer imports the legacy handler. |
| Filter: `JwtRequestFilter` | 1 | **REMOVE.** WMS uses Spring Security OAuth2 Resource Server JWT processing. |
| Request DTOs: `AuthRequest`, `BinRequest`, `InboundRequest`, `ItemRequest`, `OutboundRequest`, `TransferRequest`, `WarehouseRequest` | 7 | **REMOVE.** Old `/api/v1.0` payloads are not the WMS v1 contract; WMS uses module command/request records. |
| Response DTOs: `AuthResponse`, `BinResponse`, `DashboardSummaryResponse`, `ItemResponse`, `LedgerResponse`, `LowStockAlertResponse`, `StockResponse`, `WarehouseResponse` | 8 | **REMOVE.** WMS response contracts are tenant-scoped and owned by the new API services. |
| Repositories: `BinRepository`, `InventoryLedgerRepository`, `InventoryStockRepository`, `ItemRepository`, `LowStockAlertProjection`, `UserRepository`, `WarehouseRepository` | 7 | **REMOVE.** These query old entities/tables. WMS services use tenant-scoped JDBC queries against WMS schema. |
| Service interfaces: `BinService`, `InboundService`, `ItemService`, `OutboundService`, `TransferService`, `WarehouseService` | 6 | **REMOVE.** Their contracts are superseded by WMS application services. |
| Service implementations: `AppUserDetailService`, `AuthServiceImpl`, `BinServiceImpl`, `DashboardServiceImpl`, `InboundServiceImpl`, `InventoryServiceImpl`, `ItemServiceImpl`, `OutboundServiceImpl`, `TransferServiceImpl`, `WarehouseServiceImpl` | 10 | **REMOVE old implementations; preserve relevant behavior through WMS-native services.** See detailed mapping below. |
| Utility: `JwtUtil` | 1 | **REMOVE.** Legacy JJWT token utility is replaced by WMS JWT encoder/decoder and token services. JJWT dependencies are removed from the cleanup branch; final full CI is the dependency proof. |
| **Total** | **63** | **No old Logistics runtime is intended to remain.** |

### 3.1 Service behavior mapping

| Legacy implementation | WMS implementation / finding |
|---|---|
| `AppUserDetailService`, `AuthServiceImpl` | WMS credential authentication, JWT token service, invitation acceptance and controlled platform bootstrap. Public self-registration is not part of the WMS onboarding model. |
| `DashboardServiceImpl` | `WmsOperationalReportingService`. |
| `InboundServiceImpl` | `WmsInboundReceivingService` plus inventory posting and immutable ledger. |
| `InventoryServiceImpl` | `WmsInventoryPostingService`, operational reporting and rule-based replenishment planning. |
| `ItemServiceImpl` | `WmsInventoryMasterDataService`. Legacy minimum-stock alert query is not claimed to have exact endpoint parity; WMS min/max replenishment rules are the current workflow. |
| `OutboundServiceImpl` | `WmsOutboundShipmentService`, picking, packing, shipping and inventory posting. |
| `TransferServiceImpl` | `WmsInterWarehouseTransferService` and inventory posting lock-order/concurrency controls. |
| `WarehouseServiceImpl` | `WmsWarehouseManagementService`. |
| `BinServiceImpl` | Warehouse locations in `WmsWarehouseManagementService`. The old weight-capacity behavior is relevant and is being reimplemented at the WMS inventory-posting boundary in PR #161. |

### 3.2 Capacity behavior — required migration

**PARTIALLY VERIFIED → MOVE / REIMPLEMENT**

The old bin/inbound/outbound/transfer flow calculates item weight, prevents inbound or transfer stock from exceeding a bin's configured weight capacity, and updates bin occupancy.

WMS already has `items.weight_per_base_uom`, `items.weight_uom_id`, `warehouse_locations.max_weight_capacity` and `warehouse_locations.weight_uom`. However, before PR #161, WMS item master did not expose item weight and inventory posting did not enforce location weight capacity. Therefore the old behavior was not proven to be replaced merely because WMS has capacity columns.

PR #161 reimplements this behavior with WMS tenant-scoped item/location data, mass-UOM validation, tenant-serialized posting checks and MySQL regression tests. PR #161 is merged; its branch CI and subsequent Main CI run `44643783141` are successful.

## 4. Remaining 15 legacy-package tests

| Legacy test | Disposition / WMS evidence |
|---|---|
| `ZirologisticsApplicationTests` | **REMOVE.** It only loads the retired application context. WMS runtime boundary has its own test. |
| `CorsSecurityIntegrationTest` | **MOVE / REWRITE.** Replaced by `WmsCorsSecurityIntegrationTest` in merged PR #160, including allowed/foreign origins and proof that CORS does not bypass authentication. |
| `DemoAccountInitializerTest` | **REMOVE.** Old demo account flow is replaced by controlled platform bootstrap and invitation lifecycle. |
| `AuthRegistrationSecurityIntegrationTest`, `AuthServiceImplTest` | **REMOVE old tests.** WMS invitation acceptance tests and the exact anonymous auth-route allowlist protect the new onboarding model; no public self-registration route exists. |
| `InactiveAccountJwtSecurityIntegrationTest`, `AppUserDetailServiceTest` | **REMOVE old tests.** WMS credential authentication rejects inactive users; JWT conversion validates active tenant membership and token binding. |
| `GlobalExceptionHandlerTest` | **REMOVE old test.** `WmsApiErrorContractTest` protects WMS validation, not-found, conflict, forbidden, malformed JSON and safe internal-error responses. |
| `FlywayInitialSchemaIntegrationTest` | **REMOVE.** It tests the old Logistics schema; WMS migration tests protect WMS V1–V26. |
| `MySqlInventoryLockingIntegrationTest` | **REMOVE old test.** WMS inventory-posting MySQL tests protect no-oversell, idempotency, locking, ledger/balance atomicity and concurrency. |
| `InventoryStockRepositoryTest`, `InventoryServiceImplTest` | **REMOVE old tests.** Rule-based WMS min/max replenishment planning supersedes the old per-warehouse low-stock query. Exact low-stock alert endpoint parity is not claimed. |
| `InboundServiceImplTest` | **REMOVE old test.** WMS inbound receiving and inventory-posting tests cover receipt lifecycle, atomic ledger posting and idempotency. |
| `OutboundServiceImplTest` | **REMOVE old test.** WMS outbound shipment/picking/packing/shipping and inventory-posting tests cover the current workflow. Capacity parity is protected by merged PR #161. |
| `TransferServiceImplTest` | **REMOVE old test.** WMS transfer and inventory-posting tests cover transfer lifecycle, deterministic locking and opposite-direction deadlock prevention. Capacity parity is protected by merged PR #161. |

The WMS runtime-boundary test was rewritten to assert absence of legacy bean names rather than import deleted legacy classes. The WMS API error-contract test was decoupled from the legacy global exception handler.

## 5. Configuration and dependency disposition

| Legacy artifact | Disposition |
|---|---|
| Root `application.properties` | **REMOVE.** WMS configuration is explicit in `application-wms.properties`; no legacy DB/JWT fallback is retained. |
| `application-demo.properties` | **REMOVE.** Legacy demo profile is retired. |
| `application-test.properties` | **REMOVE.** Legacy test profile is retired. WMS runtime-boundary test activates only `wms` and declares its own H2 test datasource. |
| `src/main/resources/db/migration/V1__initial_schema.sql` | **REMOVE from executable classpath.** WMS Flyway uses only `db/wms-migration`. Historical source schema export remains documentation/reference data. |
| Maven `legacy-runtime` profile | **REMOVE.** WMS is the sole executable runtime. |
| JJWT dependencies | **REMOVE.** WMS uses Spring Security OAuth2 JWT components. |
| H2 test dependency | **KEEP.** WMS runtime-boundary test uses H2 to boot an isolated context. |
| WMS `application-wms.properties`, WMS migrations V1–V26 | **KEEP unchanged.** |

## 6. Final cleanup gates

1. PR #160 WMS CORS migration is merged and Main CI passed.
2. PR #161 WMS item-weight/location-capacity migration is merged and Main CI passed.
3. Cleanup branch is based directly on the latest `main` containing both migrations.
4. Final tree must contain no legacy-only production source, legacy-only tests, legacy properties, or old Logistics migration.
5. No WMS test may import a deleted legacy class; the runtime-boundary and API error-contract tests must compile against WMS-only components.
6. Full Testcontainers MySQL 8.4 regression, WMS executable Start-Class, Docker image build and Compose validation must pass on the combined cleanup branch.
7. Review exact diff and dependency tree before removing Draft status and merging.

No developer-owned database is accessed or modified. Frozen WMS migrations V1–V26 are not changed.
