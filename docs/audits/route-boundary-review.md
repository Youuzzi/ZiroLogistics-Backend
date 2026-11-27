# WMS HTTP Route and Tenant Boundary Review

**Review status:** PARTIALLY VERIFIED
**Reviewed source branch:** `main`
**Review snapshot:** main commit `d359b9db7131ec3a2b0f60783bb015248afbfc25` plus post-merge CI run #693
**Related report:** [security-verification.md](security-verification.md)

## 1. Scope and method

This review inventories the WMS HTTP controllers and follows their request boundary into application services. It checks whether:

1. HTTP controllers require the authenticated WMS principal (or an explicit public action-token flow).
2. Tenant-scoped services resolve the active membership from persisted state.
3. Services compare the resolved tenant/membership/user context with the supplied principal.
4. Operations check a named permission before using tenant-scoped data.
5. Queries use the resolved tenant ID when selecting or mutating tenant-owned resources.

This is a source-level review supported by existing tests and CI. It is not a formal penetration test and does not replace route-by-route runtime cross-tenant substitution tests.

## 2. HTTP controller inventory

The recursive source tree contains 19 WMS controller classes:

| Controller | Route family | Principal / boundary observation |
|---|---|---|
| `WmsInventoryAdjustmentController` | `/tenants/current/inventory-adjustments` | Passes authenticated principal to adjustment service for list/get/create/submit/approve/reject/post |
| `WmsRoleManagementController` | `/tenants/current/roles`, permissions, membership roles | Passes principal to role service; service checks role/permission operations |
| `WmsInvitationController` | `/invitations` | Invite and existing-account acceptance use authenticated principal; public acceptance uses action-token flow |
| `WmsInboundReceivingController` | `/tenants/current/inbound-receipts` | Passes principal to receiving service |
| `WmsInventoryController` | `/tenants/current/inventory` | Passes principal to posting and query services |
| `WmsInventoryMasterDataController` | `/tenants/current/items`, categories, UOM, lots | Passes principal to master-data service |
| `WmsOutboundShipmentController` | `/tenants/current/outbound-shipments` | Passes principal to shipment service |
| `WmsPackingManagementController` | `/tenants/current/packing-sessions` | Passes principal to packing service |
| `WmsPickingManagementController` | `/tenants/current/picking-tasks` | Passes principal to picking service |
| `WmsPutawayManagementController` | `/tenants/current/putaway-tasks` | Passes principal to putaway service |
| `WmsReplenishmentPlanningController` | `/tenants/current/replenishment-rules` | Passes principal to planning service |
| `WmsReplenishmentTaskController` | `/tenants/current/replenishment-tasks` and confirmations | Passes principal to task service |
| `WmsOperationalReportingController` | `/tenants/current/reports` | Passes principal to reporting service |
| `WmsAuthenticationController` | `/auth` | Explicit public authentication/action-token endpoints; password change requires authenticated WMS principal |
| `WmsShippingManagementController` | `/tenants/current/shipping` | Passes principal to shipping service |
| `WmsStockCountManagementController` | `/tenants/current/stock-counts` | Passes principal to stock-count service |
| `WmsTenantProfileController` | `/tenants/current/profile` | Passes principal to tenant-profile service |
| `WmsInterWarehouseTransferController` | `/tenants/current/stock-transfers` | Passes principal to transfer service |
| `WmsWarehouseController` | `/tenants/current/warehouses` and nested locations | Passes principal to warehouse service |

The tenant-scoped controllers use the WMS profile and the `WmsHttpApiEnabledCondition`; the public authentication controller uses the separate `WmsPublicAuthEndpointsEnabledCondition`. The HTTP security configuration has an authenticated-by-default JWT chain and a deny-all chain when JWT HTTP security is disabled. Public auth routes are explicit, not a blanket permit for all API paths.

## 3. Application service boundary observations

The following application services were inspected at the access-helper and operation-entry level:

- `WmsInventoryAdjustmentService`
- `WmsInboundReceivingService`
- `WmsInventoryMasterDataService`
- `WmsInventoryQueryService`
- `WmsOutboundShipmentService`
- `WmsPackingManagementService`
- `WmsPickingManagementService`
- `WmsPutawayManagementService`
- `WmsReplenishmentPlanningService`
- `WmsReplenishmentTaskService`
- `WmsOperationalReportingService`
- `WmsShippingManagementService`
- `WmsStockCountManagementService`
- `WmsInterWarehouseTransferService`
- `WmsWarehouseManagementService`
- `WmsRoleManagementService`
- `WmsTenantProfileService`
- `WmsTenantInvitationService`

For the tenant-scoped operational services, the common pattern is:

1. Resolve current membership with `WmsMembershipAccessService.resolve(userPublicId, membershipPublicId)`.
2. Compare resolved user, tenant ID/public ID, and membership ID/public ID with the principal.
3. Check an operation-specific permission such as `WMS_INBOUND_RECEIPT_POST`, `WMS_INVENTORY_READ`, `WMS_PICKING_CONFIRM`, or `WMS_TRANSFER_RECEIVE`.
4. Use the resolved tenant ID in SQL predicates and joins for tenant-owned records.

The role-management service uses operation-specific role/permission checks and tenant-scoped SQL. The tenant profile service additionally checks the persisted membership context and explicit profile-update permission. Invitation issuance resolves the current membership and checks the invite permission; acceptance uses dedicated action-token handling.

### Specific implementation examples

**Persisted membership resolution**

`src/main/java/com/zirocraft/zirologistics/wms/authorization/application/WmsMembershipAccessService.java`

The service resolves active access using both the authenticated user public ID and selected membership public ID, then builds effective permissions from persisted rows. It does not accept a tenant ID from an arbitrary request parameter as the source of authorization.

**Tenant context comparison**

Representative helpers exist in `WmsWarehouseManagementService`, `WmsInboundReceivingService`, `WmsInventoryQueryService`, `WmsStockCountManagementService`, and the other listed services. They compare the resolved persisted membership context with the authenticated principal before proceeding.

**Operation permission checks**

The services use distinct permission codes for reads and state changes. Examples:
- Inbound receipt: `WMS_INBOUND_RECEIPT_READ/CREATE/UPDATE/POST`
- Inventory query: `WMS_INVENTORY_READ` and `WMS_INVENTORY_LEDGER_READ`
- Picking: `WMS_PICKING_READ/CREATE/ASSIGN/START/CONFIRM`
- Transfer: `WMS_TRANSFER_READ/CREATE/RELEASE/DISPATCH/RECEIVE`
- Stock count: `WMS_STOCK_COUNT_READ/CREATE/PLAN/START/RECORD/REVIEW/APPROVE/CANCEL/SUBMIT`

These names are examples from source, not an exhaustive permission catalogue.

## 4. Existing runtime test coverage and its limits

Controller-level HTTP test classes found in the repository include:

- `WmsAuthenticationControllerHttpTest`
- `WmsRoleManagementControllerHttpTest`
- `WmsTenantProfileControllerHttpTest`
- `WmsInterWarehouseTransferControllerHttpTest`
- `WmsWarehouseControllerHttpTest`

They cover selected unauthenticated-route rejection, request validation, principal forwarding, and endpoint contracts. They do not constitute an HTTP integration test for all 19 controllers.

Relevant service/integration tests also exist for tenant profile, role management, tenant lifecycle/provisioning, credential authentication, replenishment planning/concurrency, and other domain invariants. The earlier Stage 16.5 report records selected negative authorization cases.

CI evidence:
- Main Backend CI #690 attempt 2: 334 tests, 0 failures, 0 errors, 2 skipped.
- Post-merge Backend CI #693: SUCCESS.
- Post-merge CodeQL run #5: SUCCESS.

The two skipped Argon2 benchmark/concurrency tests remain excluded from executed-test evidence. A successful CodeQL workflow run is not a statement that the repository has zero CodeQL alerts; alert review remains separate.

## 5. Residual risks and work required before closure

The source review did not reveal an obvious controller that directly accepts a tenant ID and performs tenant data access without passing through an application service. However, this is not proof that every object-level authorization path is safe.

Remaining runtime verification:

1. Add or identify cross-tenant substitution tests for nested resource IDs (for example shipment/line, warehouse/location, stock count/line, packing session/package, and transfer allocation).
2. Verify list/search endpoints cannot leak records from another tenant when filters contain foreign public IDs.
3. Exercise role/permission revocation between requests and around concurrent state transitions.
4. Add targeted HTTP integration coverage for high-risk workflow controllers not currently represented by controller HTTP test classes.
5. Validate trusted proxy CIDRs and forwarded-header behavior against the eventual deployment topology.
6. Review CodeQL alerts and OSV findings as findings, not only workflow status.
7. Keep ZAP DAST in WP16.6; it is not covered by this source review.

A helper in `WmsReplenishmentPlanningService` named `requirePermission` performs a second permission lookup after the public `plan` method has already called the full tenant-context `access` check. This is currently redundant rather than an observed bypass in that call path; it should not be misreported as a confirmed vulnerability.

## 6. Review disposition

**PARTIALLY VERIFIED — source route inventory completed; runtime route-wide cross-tenant proof remains open.**

The review strengthens confidence in the architecture: tenant context and permissions are enforced in application services, not solely in controllers. But the absence of HTTP integration tests for every route means WP16.5 must remain partially verified until the targeted cross-tenant cases above are tested. Do not describe the system as fully penetration-tested or production-certified.
