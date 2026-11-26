# Stage 16 — Separation of Duties Current-State Classification Audit

**Status:** Evidence-backed classification of selected current workflow controls; not a complete SoD certification.
**Scope:** Tenant WMS workflow actor separation, risk-registry metadata, integration evidence and remaining classification decisions. This document does not change runtime policy.

## 1. Classification principles

NIST distinguishes static SoD (conflicting roles cannot be assigned together) from dynamic SoD (the same actor cannot perform conflicting actions on a particular workflow instance). ZiroLogistics supports multiple roles per membership, so role assignment alone cannot establish that the person who created a specific receipt, count or adjustment is different from the person who commits or approves it.

For transaction-level controls, compare persisted actor membership identifiers at the application-service boundary. Do not treat a role name, hidden UI action, or the existence of a second role as proof of independent action.

The risk registry is metadata, not an executable SoD engine. A non-null `separationOfDutiesGroup` is a useful classification signal, but the registry does not itself enforce the actor comparisons.

## 2. Current-state workflow controls

| Workflow | Persisted actors / current rule | Runtime evidence inspected | Test evidence and remaining confidence |
|---|---|---|---|
| Inbound receipt | Receipt creator must differ from the membership posting the receipt. | `WmsInboundReceivingService.post` compares `created_by_membership_id` with the current membership before posting. | MySQL test `receivesAcceptedStockAndRejectedQuantityWithoutAddingRejectedStock` first attempts posting as the receipt creator, asserts `AccessDeniedException` and unchanged `DRAFT` state, then posts successfully as an independent membership. Strong evidence for this rule. |
| Outbound shipment | Shipment creator must differ from the membership performing final shipping. | `WmsShippingManagementService.ship` compares `created_by_membership_id` with the current membership before final ship commit. | MySQL test `packingRequiresPickedQuantitiesAndSealedPackagesBeforeShipmentBecomesPacked` attempts final shipping as the shipment creator, asserts `AccessDeniedException`, unchanged `PACKED` state and unchanged ledger count, then ships successfully as an independent membership. Strong evidence for this rule. |
| Inter-warehouse transfer | A membership that dispatched any partial quantity must not receive that transfer. The check must account for all dispatching memberships, not only the first header actor. | `WmsInterWarehouseTransferService.receive` calls the independent receiver guard. | MySQL test `transferReceiptRejectsEveryMembershipThatDispatchedAnyPartialQuantity` proves the partial-dispatch case and an independent receiver succeeds. |
| Stock count | Latest final counter cannot review its own line; recount requires another active membership; approver must differ from creator, header reviewer, every line reviewer, and every latest final counter. | `WmsStockCountManagementService.reviewLine`, `approve`, and recount logic compare persisted membership IDs. | MySQL tests include `counterCannotReviewOwnSubmittedCountLine` and `varianceRequiresIndependentReviewAndFourEyesApprovalAndRemainsFrozen`. Stronger workflow evidence than role-only separation. |
| Inventory adjustment | Creator cannot approve their own submitted adjustment and cannot post their own approved adjustment. | `WmsInventoryAdjustmentService.approve`, `post`, and `postManual` compare `created_by_membership_id` with the current membership. | Existing manual-adjustment integration coverage exercises independent approval before posting. The inspected service does not require the poster to differ from the approver; whether this is permitted must be an explicit policy decision, not an accidental assumption. |

## 3. Risk registry classification signals

The current `WmsTenantPermissionRiskRegistry` has 94 explicit entries with risk class, tenant-assignability, fresh-MFA, audit and optional SoD-group metadata.

### Named SoD groups currently present

| Group | Permission codes currently associated |
|---|---|
| `INBOUND_RECEIPT` | `WMS_INBOUND_RECEIPT_POST` |
| `OUTBOUND_SHIPMENT` | `WMS_SHIPPING_SHIP` |
| `INTER_WAREHOUSE_TRANSFER` | `WMS_TRANSFER_DISPATCH`, `WMS_TRANSFER_RECEIVE` |
| `STOCK_COUNT` | `WMS_STOCK_COUNT_REVIEW`, `WMS_STOCK_COUNT_APPROVE` |
| `INVENTORY_ADJUSTMENT` | `WMS_INVENTORY_ADJUSTMENT_APPROVE`, `WMS_INVENTORY_ADJUSTMENT_REJECT`, `WMS_INVENTORY_ADJUSTMENT_POST` |
| `REPLENISHMENT_REVERSAL` | `WMS_REPLENISHMENT_CONFIRMATION_REVERSE` |

### High-risk permissions without a named SoD group

The current registry also marks some C1 permissions as inventory commitment without assigning a SoD group, including `WMS_INVENTORY_POST`, `WMS_INVENTORY_RESERVE`, `WMS_OUTBOUND_SHIPMENT_ALLOCATE`, `WMS_PICKING_CONFIRM`, `WMS_PUTAWAY_CONFIRM`, and `WMS_REPLENISHMENT_TASK_CONFIRM`.

This is not automatically a defect: these may be execution steps rather than independent approval boundaries. It is, however, an explicit classification question for the threat model. Do not infer that every C1 permission requires a different actor, and do not infer that a missing group means no control is needed.

The registry also classifies adjustment approval/rejection/posting and stock-count review/approval as A1. A1 indicates high-impact approval/reversal risk; the exact actor relationships still need workflow-specific definitions.

## 4. Confirmed design gaps / decisions required

1. **Inbound and shipping negative tests:** add MySQL tests proving the receipt creator cannot post and the shipment creator cannot perform final shipping, with unchanged workflow and inventory state after denial.
2. **Adjustment approver versus poster:** decide whether the approver may also perform the subsequent posting step. Current code blocks creator self-approval and creator self-posting but does not block approver-equals-poster. Record the business/risk decision and test it explicitly.
3. **Replenishment reversal:** the registry assigns A1 and `REPLENISHMENT_REVERSAL`; inspect persisted confirmation actor history and verify whether reversal requires a distinct membership and whether repeated reversal is impossible.
4. **Generic inventory posting:** review direct `WMS_INVENTORY_POST` entry points and their permitted callers. Workflow-originated posting must not bypass the actor checks owned by inbound, shipping, transfer, adjustment, or replenishment services.
5. **Membership versus user identity:** decide per rule whether conflicts compare membership ID, user ID, or both. A user may hold multiple memberships; membership-only comparison may not meet a policy intended to separate human actors.
6. **Partial and multi-round workflows:** retain all relevant actor history. A header-level first actor is insufficient where several users can dispatch partial quantities, count multiple lines, or submit recounts.
7. **Registry parity:** add a test or generated report ensuring every permission code is classified and every non-null SoD group maps to a documented workflow rule. Registry metadata must not be silently treated as runtime enforcement.
8. **Configurable governance:** only rules explicitly classified as configurable may be relaxed by a tenant profile. Mandatory rules must fail closed when policy is absent, malformed or unsupported.

## 5. Recommended rule record before policy implementation

Each rule should have:
- stable rule code and workflow;
- conflicting actor roles (maker, executor, reviewer, approver, reverser);
- identity comparison key (membership, user, or both);
- transaction/line/warehouse/tenant scope;
- mandatory, configurable, threshold-driven or advisory classification;
- exact enforcement method and persisted columns used;
- behavior for partial operations, retries and recounts;
- audit evidence and denial behavior;
- explicit small/medium/enterprise defaults, where tenant configuration is permitted.

A governance profile must not grant permissions, rewrite actor history, or convert a mandatory independent approval into self-approval.

## 6. Acceptance criteria for SoD certification

- Every registry SoD group maps to one or more explicit rule definitions and workflow service enforcement points.
- Every currently enforced rule has a negative integration test proving denial with no side effects.
- Partial dispatch, line-level count review, recount and adjustment posting preserve all actor history needed for comparison.
- The adjustment approver/poster relationship and replenishment reversal actor rule are explicitly decided and tested.
- Direct service invocation and workflow-specific inventory posting cannot bypass the same SoD rule.
- Tenant-configurable rules have safe defaults and cannot weaken mandatory controls.
- Full MySQL Testcontainers, CodeQL, dependency scan, DAST and required CI pass on the reviewed commit.

## 7. References

- NIST CSRC, Role Based Access Control FAQs (Core RBAC, static SoD and dynamic SoD): https://csrc.nist.gov/projects/role-based-access-control/faqs
- NIST CSRC, Separation of Duty glossary: https://csrc.nist.gov/glossary/term/separation_of_duty
- Microsoft Learn, Cycle counting: https://learn.microsoft.com/en-us/dynamics365/supply-chain/warehousing/cycle-counting

---
This is a current-state engineering audit, not a compliance certification or claim that all SoD controls are complete.
