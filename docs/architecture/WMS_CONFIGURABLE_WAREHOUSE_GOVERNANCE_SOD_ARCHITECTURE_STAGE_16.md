# Stage 16 — Configurable Warehouse Governance, Multi-Role and SoD Architecture

**Status: PROPOSED ARCHITECTURE — no runtime behavior is changed by this document.**  
**Review date:** 2026-11-26

## 1. Product requirement

ZiroLogistics serves warehouses with materially different staffing and control maturity:
- Small / micro / UMKM warehouses may have one person receive, record, pick, pack and dispatch goods.
- Medium warehouses may have several operators and supervisors, with independent review for selected high-impact activities.
- Enterprise warehouses may require strict maker-checker-approver, delegated authority, and workflow-specific SoD.

The product must support these operating models without separate authorization systems or tenant migrations when a customer grows.

## 2. Core design principle

**One authorization engine; tenant-configurable workflow governance within non-negotiable security invariants.**

Separate four concerns:
1. **Identity and membership:** who is acting, in which tenant and membership.
2. **RBAC:** exact permissions granted through assigned roles.
3. **Governance policy:** which workflow controls, thresholds and approval paths apply to the tenant.
4. **Enforcement:** backend decision at the point of action, including direct service and asynchronous execution.

A governance profile is not a role and must never grant permissions. A role is not a workflow approval. Tenant isolation remains independent from both RBAC and governance.

## 3. Existing compatibility

The current model already stores membership-to-role assignments in `membership_roles`, and `WmsRoleManagementService.membershipRoles` returns multiple active role assignments. Effective permission resolution should be confirmed and tested as a union of permissions from all active assigned roles, with duplicate permission codes deduplicated.

The current policy contains 13 immutable tenant system-role templates and a 30-permission Tenant Owner baseline. These remain defaults and compatibility anchors; they are not a required staffing chart. A small warehouse can assign several appropriate operational templates to one genuine user membership, subject to protected controls.

The current custom-role grantability list contains 20 explicit permission codes. It must not be expanded wholesale to the 94-entry permission risk registry. Delegation must remain risk-aware and fail-closed.

## 4. Proposed governance profiles

Profiles are starting presets, not hard-coded product tiers. Tenants should be able to tighten controls and, where policy permits, relax non-mandatory workflow separation.

| Dimension | Small / UMKM | Medium | Enterprise |
|---|---|---|---|
| Role assignment | Several operational roles may be assigned to one membership | Multi-role with recommended separation | Fine-grained role assignment and explicit conflict review |
| Routine receiving and dispatch | Single operator allowed where the workflow's risk policy permits | Operator/controller split recommended or required by configured policy | Maker/checker separation according to SOP |
| Picking and packing | Same operator can perform both | Configurable | Commonly separated by workflow/zone |
| Stock count | Same person may count and record; discrepancies can trigger owner review based on configured thresholds | Reviewer for defined variance bands | Counter, reviewer and approver independently separated |
| Inventory adjustment | Reason code, quantity/value threshold, audit; high-risk action remains protected | Approval for configured thresholds and sensitive items | Independent approval and strict posting/reversal governance |
| Transfer | One operator may handle routine workflow only if no mandatory two-person control is violated | Dispatch/receive separation configurable by risk | Independent dispatcher and receiver |
| Approval routing | Owner/authorized supervisor; avoid blocking routine work | Supervisor/manager tiers by risk | Multi-level approval and delegated authority |
| Audit and traceability | Always on | Always on | Always on, potentially extended retention/export |

These are proposed operating defaults, not statements that the current application already supports these modes.

## 5. Non-negotiable invariants

No governance profile may disable:
- Tenant isolation and server-resolved tenant context.
- Authenticated, attributable human or explicitly scoped system identity.
- Exact permission checks and fail-closed unknown permission handling.
- Atomic inventory posting, ledger consistency and idempotency controls.
- Audit trail for inventory-affecting and privileged governance actions.
- Fresh MFA for permissions/actions classified as requiring it.
- Platform/tenant identity and authorization separation.
- Mandatory SoD controls for actions classified as non-waivable.
- Revocation and current-membership revalidation.

A single-operator mode is not self-approval. Where an independent approver is mandatory, the system must block or route to an eligible independent approver; it must not record the same actor as an independent approval.

## 6. SoD policy model

Avoid a single global boolean such as `sodEnabled`. Model each workflow control explicitly.

Each rule should define:
- Stable rule code and workflow (inbound, shipping, transfer, stock count, adjustment, reversal).
- Actor roles/permission groups involved (maker, executor, reviewer, approver).
- Conflict relation: same actor forbidden, same membership forbidden, or same user forbidden, according to threat model.
- Scope: tenant, warehouse, transaction, line, or approval instance.
- Enforcement point and persisted actor evidence used for comparison.
- Severity: mandatory/non-waivable or tenant-configurable.
- Allowed policy modes and safe default.
- Required audit evidence and denial behavior.

Prefer history-based workflow checks against persisted actor IDs on the business transaction. Role-combination checks alone cannot prove that the person who created a specific receipt or shipment is different from the person posting it.

### Proposed rule classes

- **Mandatory:** controls protecting cross-tenant boundaries, privileged platform actions, critical approval integrity, and risk-registry C1/A1 controls where the registry requires independent authorization.
- **Configurable:** separation for lower-risk routine workflow stages where the business can accept single-operator execution and compensating controls are available.
- **Threshold-driven:** approval becomes mandatory when quantity, value, variance, item criticality or transaction type exceeds a tenant-configured limit.
- **Advisory:** recommended segregation that produces warnings/reports but does not block the operation.

The exact mandatory/configurable classification must be approved from the current permission risk registry and workflow threat analysis before implementation. Do not infer that every C1/A1 rule is waivable.

## 7. Single-operator workflow behavior

For a permitted single-operator path:
1. The user must hold every exact permission required for each step.
2. Each step records the same authenticated actor where appropriate; the system must not fabricate a second identity.
3. Inventory commits remain atomic, idempotent and ledger-backed.
4. High-impact differences or threshold breaches route to independent approval if required.
5. The audit trail captures the action, actor, tenant, target, reason, outcome and UTC timestamp.
6. Reversal is a new controlled transaction, never silent mutation of the original ledger.
7. The UI should make the tenant's policy and any pending approval visible before work begins.

For workflows with mandatory independent SoD, the UI must explain that the operation is pending another authorized person rather than offering a bypass.

## 8. Threshold and approval policy

Potential per-tenant controls, subject to domain-specific validation:
- Maximum quantity variance before review.
- Maximum monetary/value variance before review.
- Item classes or serialized/lot-tracked goods requiring review.
- Adjustment and reversal reason codes.
- Approval levels and eligible approver permission groups.
- Warehouse-specific stricter policies.
- Temporary emergency delegation with expiry, scope and audit, where permitted.

Thresholds must use a consistent unit/currency and deterministic rounding. Do not let a tenant set thresholds that override a mandatory risk rule. Configuration changes require dedicated permission, audit, optimistic concurrency/versioning and safe defaults.

## 9. Multi-role and custom-role behavior

- One membership may hold multiple active roles.
- Effective permissions are the union of the exact permission codes from active roles; duplicates are removed.
- Removing/deactivating a role removes only grants not provided by another active role.
- Permission changes and membership role changes take effect on the next authorization resolution; no stale token permission snapshot may preserve revoked authority.
- Custom roles are composed only from explicitly tenant-assignable permissions.
- Grantability is separate from catalogue visibility and from the caller's delegation ceiling.
- C1/A1, protected workflow, platform, and SoD-sensitive permissions remain excluded unless a documented risk decision explicitly allows otherwise.
- System templates are immutable server-managed templates; tenant custom roles cannot impersonate template codes or mutate template grants.

## 10. Data and implementation direction

Do not add a tenant profile column or migration until the policy model and backward-compatible default are agreed.

A future policy model may use versioned tenant governance configuration with:
- profile preset identifier;
- per-rule mode (mandatory/configurable/advisory);
- typed threshold values with units/currency;
- approval routing references;
- effective-from and updated-at UTC timestamps;
- actor and audit reference;
- optimistic version.

Prefer normalized, typed policy data over arbitrary JSON for fields used in authorization decisions. If JSON is used for extensible metadata, validate it against a versioned schema and never interpret unknown fields as permission grants.

All runtime enforcement must load a trusted tenant policy and fail closed when a mandatory rule is missing, invalid or unsupported. Policy edits must not weaken mandatory rules.

## 11. Rollout strategy

1. **Audit only:** derive current behavior and classify each SoD rule as mandatory, configurable, threshold-driven or advisory.
2. **Compatibility baseline:** define a default policy that preserves every existing mandatory SoD behavior; do not silently relax existing production tenants.
3. **Model and tests:** add versioned policy representation and policy evaluator with unit tests.
4. **MySQL integration:** prove persistence, tenant scoping, optimistic concurrency, audit and fail-closed defaults.
5. **Workflow integration:** apply policy at the application service/domain boundary and test direct invocation.
6. **Tenant admin UX/API:** only after backend contract is stable; expose understandable presets and explain consequences.
7. **Migration/rollout:** backfill existing tenants to the strict compatibility baseline, then permit explicit tenant opt-in to relaxed supported rules.
8. **Observability:** report blocked actions, pending approvals, policy changes and SoD violations without leaking cross-tenant data.

## 12. Acceptance criteria

- Small, medium and enterprise presets have explicit, reviewed rule definitions.
- Profiles never grant permissions and cannot bypass identity, tenant, MFA, audit or mandatory SoD.
- Multi-role union/deduplication/revocation is tested.
- A single-operator workflow succeeds only for explicitly configurable steps.
- Mandatory independent approvals cannot be self-approved, including by role combination.
- Threshold boundaries, units, rounding, concurrency and policy-version behavior are tested.
- Policy changes are tenant-scoped, audited and concurrency-safe.
- Existing tenants preserve current protections until an explicit compatible policy migration.
- Backend tests prove direct-service enforcement and no side effects on denial.

## 13. Industry references and design rationale

- NIST RBAC model: Core RBAC, role hierarchy, static SoD and dynamic SoD are separable model components. This supports keeping multi-role assignment and workflow-time constraints distinct: https://csrc.nist.gov/projects/role-based-access-control/faqs
- NIST Separation of Duty: SoD can be static (role conflicts) or dynamic (enforced at action time, e.g. two-person rule): https://csrc.nist.gov/glossary/term/separation_of_duty
- Microsoft Dynamics 365 warehouse workers: a worker can have multiple warehouse work users and different mobile-menu access; cycle-count supervisor settings can determine whether count discrepancies require another review: https://learn.microsoft.com/en-us/dynamics365/supply-chain/warehousing/manage-warehouse-workers
- Microsoft Dynamics 365 cycle counting: count processing and discrepancy review are distinct workflow steps: https://learn.microsoft.com/en-us/dynamics365/supply-chain/warehousing/cycle-counting
- AWS SaaS Architecture Fundamentals: tenant isolation is separate from authentication and authorization and must be enforced for each tenant resource: https://docs.aws.amazon.com/whitepapers/latest/saas-architecture-fundamentals/tenant-isolation.html
- AWS SaaS identity: tenant context must be bound to user identity and flow through the SaaS architecture: https://docs.aws.amazon.com/whitepapers/latest/saas-architecture-fundamentals/saas-identity.html

## 14. Scope disclaimer

This document proposes architecture only. It does not assert that governance profiles, configurable SoD, approval thresholds, or single-operator exceptions are implemented. Runtime changes require separate implementation PRs, tests, security review and CI.
