# WMS Authorization Enforcement Audit

## Scope and status

This is an evidence-backed first pass of the Stage 16 authorization enforcement audit against the current `main` source tree after the Stock Count SoD and permission-risk registry merges. It is not a repository-wide security certification. The inventory below records the 19 WMS HTTP controller classes found under the WMS package, representative application-service permission gates, and the one production worker class found by the current source-tree scan. Repository-wide SQL and every service entry point remain subject to deeper review.

HTTP authentication is only the outer boundary. Tenant permission checks, tenant-scoped data access, workflow state checks, separation-of-duties checks, and audit writes must remain enforced by application services so that non-HTTP callers cannot bypass them.

## Controller-to-service inventory

The following controller classes were enumerated from `src/main/java/com/zirocraft/zirologistics/wms/**/http/*Controller.java`. Controllers extract a WMS principal from Spring Authentication where applicable and delegate business operations; this inventory is a routing map, not proof that each delegated service method is fully secure.

| Controller | Delegated application service | Surface |
|---|---|---|
| `WmsInventoryAdjustmentController` | `WmsInventoryAdjustmentService` | Adjustment read/create/submit/approve/reject/post |
| `WmsRoleManagementController` | `WmsRoleManagementService` | Tenant roles, permission catalogue, membership-role assignment |
| `WmsInvitationController` | `WmsTenantInvitationService`, `WmsInvitationAcceptanceService` | Tenant invitation issue and acceptance |
| `WmsInboundReceivingController` | `WmsInboundReceivingService` | Inbound receipt create/update/post |
| `WmsInventoryController` | Inventory query/posting services | Inventory reads and posting endpoints |
| `WmsInventoryMasterDataController` | `WmsInventoryMasterDataService` | Item, category, lot/serial master data |
| `WmsOutboundShipmentController` | `WmsOutboundShipmentService` | Shipment create/update/allocate/release |
| `WmsPackingManagementController` | `WmsPackingManagementService` | Packing lifecycle |
| `WmsPickingManagementController` | `WmsPickingManagementService` | Picking lifecycle |
| `WmsPutawayManagementController` | `WmsPutawayManagementService` | Putaway lifecycle |
| `WmsReplenishmentPlanningController` | `WmsReplenishmentPlanningService` | Replenishment rule and planning operations |
| `WmsReplenishmentTaskController` | `WmsReplenishmentTaskService` | Replenishment task lifecycle |
| `WmsOperationalReportingController` | `WmsOperationalReportingService` | Operational reporting |
| `WmsAuthenticationController` | WMS credential authentication services | Tenant authentication |
| `WmsShippingManagementController` | `WmsShippingManagementService` | Final shipping |
| `WmsStockCountManagementController` | `WmsStockCountManagementService` | Stock count lifecycle/review/approval |
| `WmsTenantProfileController` | `WmsTenantProfileService` | Current tenant profile |
| `WmsInterWarehouseTransferController` | `WmsInterWarehouseTransferService` | Transfer create/release/dispatch/receive |
| `WmsWarehouseController` | `WmsWarehouseManagementService` | Warehouse/location master data |

## Service-level evidence inspected

| Service | Exact permission gates observed | Tenant / workflow evidence observed | Assessment |
|---|---|---|---|
| `WmsInboundReceivingService` | READ, CREATE, UPDATE, POST permission codes are passed through its access helper; the helper resolves the active membership through `WmsMembershipAccessService`. | Receipt, line, warehouse, item, partner and location queries include tenant predicates; receipt posting uses conditional status transitions. | Strong first-pass evidence; continue SQL and audit failure review. |
| `WmsOutboundShipmentService` | READ, CREATE, UPDATE, ALLOCATE, RELEASE permission codes are checked through its access helper. | Shipment, line, allocation, warehouse, location, item and lot queries are tenant-scoped. | Final shipping is in the separate shipping service and needs its own review. |
| `WmsInterWarehouseTransferService` | READ, CREATE, RELEASE, DISPATCH, RECEIVE permission codes are checked through its access helper. | Transfer/line/allocation/serial/location queries carry tenant predicates; dispatch and receive have locked state transitions and independent-actor checks. | SoD and tenant scoping present in inspected paths; continue concurrency/replay review. |
| `WmsStockCountManagementService` | READ, CREATE, PLAN, START, SUBMIT, REVIEW, APPROVE, CANCEL and RECORD permission codes are checked through its access helper. | Count/scope/line/result queries are tenant-scoped; review checks the submitted result's `counted_by_membership_id` against the reviewer membership and denies same-actor review. | Counter/reviewer separation now has a MySQL integration test; reviewer/approver and multi-round semantics still need end-to-end audit. |
| `WmsInventoryAdjustmentService` | READ, CREATE, SUBMIT, APPROVE, REJECT and POST permission codes are checked through its access helper. | Adjustment allocations join tenant-matched item/warehouse/location/lot rows; approval and posting use state predicates and independent-actor controls. | First-pass evidence only; inspect all serial/lot paths and rollback/audit semantics. |
| `WmsRoleManagementService` | ROLE_READ, ROLE_CREATE, ROLE_UPDATE, ROLE_DEACTIVATE, PERMISSION_CATALOG_READ, ROLE_PERMISSION_MANAGE, MEMBERSHIP_ROLE_READ and MEMBERSHIP_ROLE_MANAGE are checked through its access helper. | Role and membership joins include tenant predicates; role mutation uses version/status predicates. | First-pass evidence; inspect delegated permission allowlist and owner-protection edge cases. |
| `WmsInventoryPostingService` | Public posting paths resolve a WMS membership and include permission checks for manual/approved adjustment posting; workflow-specific entry points are called by domain services. | Posting validates tenant-owned warehouse/location/item/lot/serial records and uses tenant-scoped balance/ledger updates. | Must audit each public entry point and caller capability separately; do not infer that a private helper secures all callers. |

## Worker and platform boundary inventory

- The current production WMS source-tree scan found `WmsEmailOutboxDeliveryWorker` as the worker class matching Worker/Listener/Scheduler/Consumer naming. It is property-gated and claims bounded batches through `WmsEmailOutboxStoragePort`. The worker is an email delivery process, not a tenant warehouse-command executor. Storage tenant scope, claim leases, retry/idempotency, secret handling and operational audit remain to be reviewed in depth.
- Platform control-plane code is under `wms/platform` and contains a separate `PlatformPermissionEvaluator`, platform credential authentication, platform JWT/security configuration, operator role administration, support grants and tenant lifecycle services. The current tree scan found no production `*Controller.java` under that platform package. Verify actual HTTP route registration and filter-chain coverage before asserting the control plane is fully exposed or fully protected.
- No worker inventory result is proof that no asynchronous execution exists: search for scheduling annotations, event listeners, executor submissions, callbacks and outbox consumers using broader patterns before sign-off.

## Enforcement invariants

1. Controllers must not accept tenant IDs as authority. Tenant identity comes from the authenticated principal and is revalidated by the application service.
2. Every tenant-facing application-service entry point must require its exact permission before reading or mutating protected resources, or require an explicitly documented narrow system capability.
3. Reads and writes must include tenant predicates on primary rows and joined child rows; public IDs are identifiers, not authorization.
4. State-changing operations must lock the aggregate and use conditional state transitions so stale or concurrent requests fail closed.
5. Inventory commitment and independent approval operations must enforce SoD rules inside the service transaction, not only in controller annotations or UI.
6. Workers must use narrowly scoped storage ports, bounded claims, explicit tenant context where tenant data is touched, and auditable outcomes. A worker must not inherit an ambient HTTP principal.
7. Audit failures for critical operations must abort the transaction; security-relevant actions must not be silently accepted without durable evidence.
8. Internal service calls, scheduled jobs, event listeners, retry paths and direct service invocations are part of the attack surface and must receive the same scrutiny as HTTP routes.
9. Platform operations must use platform principals and the separate platform permission evaluator; tenant principals and tenant roles must not authorize platform operations.

## Remaining audit work before sign-off

- Verify every controller method in the 19-class inventory against the exact service method and permission code, including authentication/invitation exceptions.
- Enumerate all public application-service methods and prove no mutating entry point can be called without a permission-bearing principal or a narrowly scoped system capability.
- Review repository SQL for tenant predicates, especially joins, subqueries, bulk updates, generated queries and native queries; prioritize cross-tenant negative tests.
- Search all async mechanisms beyond class naming: `@Scheduled`, `@EventListener`, `@Async`, executor submissions, message callbacks and outbox processors.
- Inspect platform filter-chain matchers, route registrations, support-grant scope enforcement, revocation and audit evidence.
- Add/verify negative integration tests for direct service invocation, cross-tenant identifiers, missing permissions, revoked memberships, stale workflow states, worker replay and platform/tenant principal confusion.
- Re-run the full MySQL Testcontainers suite, static analysis, dependency scanning, CodeQL and DAST after audit fixes.

## Sign-off criteria

This audit can be marked complete only when the endpoint-to-permission inventory and service/worker inventory are checked against the current source tree, all identified gaps are fixed, and negative tests prove denial without side effects. This document is an audit work product and does not itself certify completion.
