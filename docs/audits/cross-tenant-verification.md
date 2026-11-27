# Cross-Tenant Verification Evidence

**Status: IN PROGRESS — evidence inventory, not a security certification.**  
**Review date:** 2026-11-26  
**Base commit at review:** `be1722e74b537c9ef02c4d192974d258754f4bd7`  
**Active test PR:** [#334 — Stock Count Cross-Tenant Isolation](https://github.com/Youuzzi/ZiroLogistics-Backend/pull/334)

## Purpose and method

This review inspects named MySQL/Testcontainers and service-level tests for tenant A/B ownership boundaries. It prioritizes direct service invocation because controller-only authorization is not sufficient to protect internal callers. Assertions are treated as evidence only for the paths they exercise; they do not prove every query, route, or workflow is isolated.

## Verified test evidence inspected

| Domain | Test evidence | Assertions observed | Scope limitation |
|---|---|---|---|
| Tenant profile | `WmsTenantProfileMySqlIntegrationTest.rejectsForgedCrossTenantPrincipalAndInvalidProfileValues` | Forged principal whose tenant differs from persisted membership is rejected. | Profile service path only. |
| Inbound | `WmsInboundReceivingMySqlIntegrationTest.inboundReceiptsAndReferencesAreIsolatedAcrossTenants` | List/detail are tenant-scoped; foreign warehouse, supplier, location, item and receipt references are rejected; receipt, line and audit state remain unchanged. | Inbound receiving paths exercised by this test. |
| Inventory posting | `WmsInventoryPostingMySqlIntegrationTest.crossTenantInventoryPostingRejectsForeignAndMixedOwnershipReferencesWithoutSideEffects`; `crossTenantSerialReferenceIsRejectedWithoutChangingForeignSerialOrInventory` | Foreign and mixed-tenant warehouse/location/item references, plus foreign serial/lot references, are rejected; tenant balances, serial state and ledger invariants are checked. | Posting commands and represented reference types. |
| Outbound / picking / packing | `WmsOutboundShipmentMySqlIntegrationTest.outboundShipmentAndPickingResourcesAreTenantIsolatedAcrossWorkflowServices`; `outboundShipmentRejectsForeignTenantWarehouseCustomerAndItemReferencesWithoutSideEffects` | Foreign shipment, picking task and packing session detail reads are denied; foreign warehouse/customer/item references are rejected; state and reservation remain unchanged. | Specific shipment and nested workflow paths, not every route. |
| Putaway | `WmsPutawayManagementMySqlIntegrationTest.tenantCannotReadForeignPutawayTaskOrItsNestedLines` | Foreign putaway task and nested line access are denied; foreign task remains owned by the original tenant. | Putaway task/detail path. |
| Warehouse | `WmsWarehouseTenantIsolationMySqlIntegrationTest.warehouseListAndDetailAreTenantScopedAndForeignLocationParentIsRejected` | Warehouse list/detail isolation and foreign-parent location rejection are asserted. | Warehouse/location service paths. |
| Reporting | `WmsOperationalReportingMySqlTenantIsolationTest.operationalInventorySummaryIncludesOnlyTheAuthenticatedTenantsInventory` | Tenant A summary excludes tenant B inventory. | Inventory summary report, not every report query. |
| Transfer | `WmsOutboundShipmentMySqlIntegrationTest.interWarehouseTransfersAreIsolatedAcrossTenantsForReadAndCreation` | Transfer list/detail is tenant-scoped; foreign warehouse/item/location ownership prevents cross-tenant transfer creation. | Transfer paths represented in the test. |
| Replenishment | `WmsReplenishmentPlanningMySqlIntegrationTest` manual-task tenant boundary cases | Tenant A cannot read a foreign task or create a task using foreign tenant resources/lot. | Manual task operations and represented resources. |
| Stock count | `WmsStockCountMySqlIntegrationTest.inventoryAdjustmentReadApisAreTenantScoped` plus PR #334 | Existing adjustment read isolation; PR #334 adds count list/detail/workflow-command tenant A/B regression with unchanged-state assertions. | New PR evidence remains pending until CI passes. |
| Tenant role authorization | `WmsRoleManagementServiceTest` | Tenant mismatch and missing permission are denied before protected JDBC work. | Mock/service-level evidence, not MySQL tenant A/B workflow. |

## Stock count regression added in PR #334

The new MySQL Testcontainers test `stockCountListDetailAndWorkflowCommandsAreIsolatedAcrossTenants` creates independent tenant fixtures and planned counts. It asserts:

1. Tenant A's list includes its own count and excludes tenant B's count.
2. Tenant B can still read its own count.
3. Tenant A cannot read or plan tenant B's count by public ID.
4. Tenant B's count remains `PLANNED`, and tenant A's own count remains `PLANNED` after denied requests.

This protects against public-ID substitution and checks denial without workflow side effects. The test is not considered verified until the latest PR head passes Backend CI.

## Current implementation status reflected in main

The following PRs are merged:
- #330 — tenant permission risk registry and MySQL catalogue parity.
- #331 — stock-count counter/reviewer separation.
- #332 — platform privileged delegation expiry checked against database UTC.
- #333 — authorization enforcement audit inventory and remaining sign-off criteria.

Stock-count approval code also checks that the approver differs from the count creator, every line reviewer, and every final counter. The MySQL integration suite contains explicit denial assertions for creator/counter/reviewer identities and success for a separate approver. This is stronger than merely requiring a different reviewer at the line level.

## Residual risks and unclosed gates

1. The inspected test matrix is broad but not exhaustive across every controller method, nested resource, native query, bulk update, and worker path.
2. Authenticated ZAP evidence currently exercises a small set of protected read routes; it is not a write-workflow or full-controller DAST certification.
3. Read-only k6 evidence is not representative of inventory posting, receiving, picking, packing, shipping, transfer, stock count, or replenishment throughput.
4. Platform support-grant and operator-role governance have focused tests, but all platform route exposure, support-grant consumption, revocation races, and action-level audit evidence still require a complete route-to-service review.
5. The email outbox worker is the scheduled WMS worker found in the current source search. Storage lease fencing, retry/idempotency and secret handling have focused tests/docs, but no claim is made that every possible asynchronous execution mechanism has been exhaustively enumerated.
6. The authorization audit document is explicitly a first pass; service entry points, SQL tenant predicates, and all HTTP routes remain to be checked before certification.
7. Full regression, CodeQL, strict OSV and authenticated DAST must pass on the final integrated commit after all accepted changes are merged.

## Closure rule

Do not mark Stage 16 complete based on this document or the count of passing PRs. Closure requires the latest source-tree route/service/worker inventory, remediation of confirmed gaps, negative tests for high-risk cross-tenant paths, and successful full CI/security checks on the final integrated commit. Record any accepted residual risk with an owner, rationale and review/expiry date.
