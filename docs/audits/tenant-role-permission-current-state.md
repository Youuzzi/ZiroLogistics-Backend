# Tenant RBAC Current-State and Role/Permission Matrix Audit

**Status: AUDIT BASELINE — not a certification and not an authorization change.**
**Original review baseline:** `main` at `be1722e74b537c9ef02c4d192974d258754f4bd7`  
**Current source checkpoint reconciled:** `main` at `148177826c5eebd56b6134851e5d901809549448` (the later documentation merges do not change runtime Java source).
**Review date:** 2026-11-26

## 1. Purpose

Record what the current tenant RBAC implementation actually defines, distinguish it from proposed governance behavior, and identify gaps before changing runtime authorization. This document is intentionally evidence-oriented. A role template is a default grant set, not a job description or proof that every endpoint is correctly enforced.

## 2. Current implementation facts

| Concern | Current evidence / behavior | Assessment |
|---|---|---|
| Tenant membership to roles | `membership_roles` is queried by tenant, membership and role; `WmsRoleManagementService.membershipRoles` returns active assignments. | Multiple roles per membership are structurally supported; verify integration coverage for additive effective permissions and removal/revocation. |
| Role to permissions | `role_permissions` joins the tenant role to global `permissions`; system templates carry exact permission-code lists in `WmsTenantPermissionPolicy`. | Exact-code RBAC; no wildcard grant model in the inspected policy. |
| Effective authorization | Service resolves persisted membership access on each request and compares tenant, membership and user context against the authenticated principal. | Good revocation posture; still requires endpoint/service/worker-wide enforcement audit. |
| System templates | 13 immutable tenant templates are declared in `WmsTenantPermissionPolicy.systemRoleTemplates()`, including Tenant Administrator and operational templates. | Templates are defaults; not mandatory one-person-per-role staffing. |
| Tenant Owner | Exact 30-permission baseline in `tenantOwnerTemplate()`. | Governance/read baseline; not a warehouse superuser. |
| Custom role catalogue | `catalogueVisiblePermissionCodes()` contains 20 explicit codes. | Visibility is narrower than the complete global permission registry. |
| Custom role delegation | `delegablePermissionCodes()` contains 20 explicit codes; requested grants must also be held by the acting membership. | Fail-closed, but restrictive for tenants who want custom operational roles. Do not simply expand to every permission. |
| System role protection | System role grants are protected from custom-role permission replacement; reserved system codes cannot be created as custom roles. | Retain these protections. |
| Self-assignment | Six system roles are explicitly self-assignment restricted. | Review against the full risk registry and role assignment authority matrix. |
| Auditing | Role create/update/deactivate, permission replacement and membership role assign/remove have tenant audit event types. | Verify audit completeness, actor identity and failure outcomes in integration tests. |
| Permission risk metadata | Immutable `WmsTenantPermissionRiskRegistry` records risk class, tenant assignability, MFA, audit and SoD group metadata. | Metadata classifies risk; it does not itself grant access or enforce every control. |

## 3. Current role template inventory

The following is the current template catalogue, not a recommendation that every tenant must use every role.

| Template code | Intended operational responsibility |
|---|---|
| `SYS_TENANT_ADMINISTRATOR` | Tenant profile and master-data administration; no inventory posting or role governance. |
| `SYS_WAREHOUSE_SUPERVISOR` | Coordinate warehouse tasks and assignments; no inventory posting or approval authority. |
| `SYS_INBOUND_OPERATOR` | Create/process inbound receipts and putaway; no receipt posting. |
| `SYS_INBOUND_CONTROLLER` | Independently post completed inbound receipts. |
| `SYS_OUTBOUND_OPERATOR` | Prepare shipments, reserve stock, pick and pack; no final ship commit. |
| `SYS_OUTBOUND_CONTROLLER` | Release allocated outbound shipments. |
| `SYS_SHIPPING_CLERK` | Final shipping handoff. |
| `SYS_INVENTORY_CONTROLLER` | Prepare counts/adjustments and execute routine replenishment. |
| `SYS_INVENTORY_APPROVER` | Review/approve count variances and inventory adjustments. |
| `SYS_TRANSFER_DISPATCHER` | Create, release and dispatch inter-warehouse transfers. |
| `SYS_TRANSFER_RECEIVER` | Receive dispatched transfers. |
| `SYS_REPLENISHMENT_OPERATOR` | Execute replenishment tasks without completed-confirmation reversal. |
| `SYS_REPORTING_AUDITOR` | Read-only operational reports and workflow evidence. |

## 4. Role-to-permission matrix — current default grants

Legend: `R` = read; `W` = prepare/manage/execute non-final workflow; `C` = commit/release/ship/dispatch/receive; `A` = independent review/approval/post. The detailed canonical permission-code lists remain the source of truth in `WmsTenantPermissionPolicy`; this functional matrix is a navigational summary and must not be used to generate grants.

| Functional capability | Tenant Admin | Supervisor | Inbound Op | Inbound Ctrl | Outbound Op | Outbound Ctrl | Shipping | Inventory Ctrl | Inventory Approver | Transfer Dispatch | Transfer Receive | Replenishment Op | Reporting Auditor |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Tenant/master-data administration | W | — | — | — | — | — | — | limited | — | — | — | — | — |
| Warehouse/location/item read | R | R | R | R | R | — | — | R | — | R | R | R | R |
| Inbound receipt create/update | — | — | W | — | — | — | — | — | — | — | — | — | — |
| Inbound receipt post | — | — | — | C | — | — | — | — | — | — | — | — | — |
| Putaway create/start/confirm | — | assign | W | — | — | — | — | — | — | — | — | — | — |
| Outbound shipment prepare/allocate | — | — | — | — | W | — | — | — | — | — | — | — | — |
| Inventory reserve | — | — | — | — | C | — | — | — | — | — | — | — | — |
| Outbound release | — | — | — | — | — | C | — | — | — | — | — | — | — |
| Picking/packing | — | assign | — | — | W | — | — | — | — | — | — | — | — |
| Final ship commit | — | — | — | — | — | — | C | — | — | — | — | — | — |
| Stock count prepare/record/submit | — | — | — | — | — | — | — | W | — | — | — | — | — |
| Stock count review/approve | — | — | — | — | — | — | — | — | A | — | — | — | — |
| Adjustment create/submit | — | — | — | — | — | — | — | W | — | — | — | — | — |
| Adjustment approve/reject/post | — | — | — | — | — | — | — | — | A | — | — | — | — |
| Transfer create/release/dispatch | — | — | — | — | — | — | — | — | — | C | — | — | — |
| Transfer receive | — | — | — | — | — | — | — | — | — | — | C | — | — |
| Replenishment execution | — | assign | — | — | — | — | — | W | — | — | — | W | — |
| Operational reporting | R | R | scoped | scoped | scoped | scoped | scoped | scoped | scoped | scoped | scoped | scoped | R |

The exact role permission arrays must be extracted from source for any executable or machine-readable matrix. The summary above is intentionally not a permission source and does not imply that a role has an entire module's permissions.

## 5. Permission risk classes

The registry defines:
- **G1** — tenant governance.
- **M1** — master-data mutation.
- **R1** — operational read.
- **O1** — operational execution that does not itself commit inventory.
- **C1** — inventory commitment (reserve, inbound post, ship, transfer dispatch/receive, direct posting).
- **A1** — independent approval/reversal.

Risk metadata is not authorization. Runtime grant decisions must continue to use exact permission codes, the actor's delegation authority, tenant policy, and protected-template constraints.

## 6. Confirmed gaps / questions for follow-up

1. Produce a generated, exact-code matrix directly from the 13 source templates and 94 registry entries; avoid maintaining duplicated handwritten grant lists.
2. Add explicit integration coverage proving one membership can hold multiple roles and receives the union of their exact permissions, with duplicates removed.
3. Verify removing one role immediately removes only permissions no longer granted by another assigned role.
4. Review the 20-code custom-role delegable allowlist against risk metadata. Determine which additional low-risk R1/O1/M1 permissions are safely delegable, and which C1/A1 or SoD-bound permissions must remain protected.
5. Separate permission visibility, permission grantability, and caller delegation authority as distinct policy decisions.
6. Audit role assignment authority, privilege escalation, self-assignment, last-owner protection, role deactivation and existing assignment behavior.
7. Verify every permission is enforced in controller, service, worker and direct-call paths; UI hiding is not enforcement.
8. Do not interpret the existing six self-assignment restrictions as the complete SoD policy. SoD is also enforced against persisted workflow actors and must be reviewed per workflow.
9. No tenant-facing governance profile currently changes SoD behavior. Any future configurable policy requires a server-enforced policy model and migration/compatibility design.

## 7. Acceptance criteria for RBAC certification

- Exact generated matrix covers every system template and every permission code.
- Every permission has a risk classification and explicit grantability decision.
- Multi-role union, deduplication, deactivation and revocation are proven with MySQL integration tests.
- Custom-role grant decisions fail closed for unknown, non-assignable, protected, unheld or unauthorized permissions.
- Role assignment cannot elevate the actor beyond their delegation authority.
- SoD constraints are enforced by backend workflow services, not only role combinations or UI.
- Audit evidence records actor, tenant, target, operation, outcome and UTC time for successful privileged changes; denied attempts follow the agreed security audit policy.
- Full required CI and security workflows pass on the exact reviewed commit.

## 8. References

- NIST CSRC, Role Based Access Control FAQs: https://csrc.nist.gov/projects/role-based-access-control/faqs
- NIST CSRC, Separation of Duty glossary: https://csrc.nist.gov/glossary/term/separation_of_duty
- Microsoft Learn, Manage warehouse workers: https://learn.microsoft.com/en-us/dynamics365/supply-chain/warehousing/manage-warehouse-workers
- Microsoft Learn, Cycle counting: https://learn.microsoft.com/en-us/dynamics365/supply-chain/warehousing/cycle-counting
- AWS SaaS Architecture Fundamentals, Tenant isolation: https://docs.aws.amazon.com/whitepapers/latest/saas-architecture-fundamentals/tenant-isolation.html

## 9. Scope disclaimer

This is a current-state audit baseline, not a claim of full RBAC certification, exhaustive permission enforcement, or compliance certification. Findings must be validated against the current main branch and subsequent implementation PRs.
