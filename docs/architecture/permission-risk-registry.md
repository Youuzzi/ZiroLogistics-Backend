# WMS Permission Risk Registry

**Status:** Contract for implementation; this document does not claim that machine-readable risk metadata is already enforced.
**Scope:** Tenant WMS permissions only. Platform control-plane permissions remain a separate catalogue and authorization boundary.

## 1. Why this registry exists

The permission catalogue currently stores a stable permission code, module, and description. Tenant role templates and delegation policy are centralized, but risk classification is not a canonical machine-readable attribute. A permission's risk must not be inferred independently by controllers, role templates, UI code, or migration descriptions.

The registry is policy metadata. It does not grant a permission, replace tenant RBAC, bypass resource ownership, or replace workflow state validation.

## 2. Required metadata contract

Every seeded tenant WMS permission must resolve to exactly one immutable registry entry:

| Field | Requirement |
|---|---|
| `code` | Exact existing permission code; no aliases or wildcard matching |
| `module` | Must match the seeded permission catalogue module |
| `riskClass` | One of G1, M1, R1, O1, C1, A1 |
| `tenantAssignable` | Explicitly states whether tenant custom roles may receive it; independent from catalogue visibility |
| `freshMfaRequired` | True for high-impact actions according to the approved risk policy; evaluated at action time |
| `auditRequired` | True for all mutations and all C1/A1 operations |
| `separationOfDutiesGroup` | Optional stable workflow group for operations requiring independent actors |
| `description` | Human-readable intent consistent with the seeded catalogue |

The initial implementation should be an immutable Java registry, not a new database table. The current catalogue is seeded by Flyway and should remain the source for persisted permission identity. Add durable columns or a new table only if runtime administration or versioned metadata requires database ownership.

## 3. Risk classes

| Class | Meaning | Typical actions |
|---|---|---|
| G1 — Tenant governance | Manage tenant membership, role configuration, and tenant profile | Invite membership, manage tenant roles |
| M1 — Master-data mutation | Change warehouse, location, item, lot, serial, or unit configuration | Create/update/status changes |
| R1 — Operational read | Read warehouse, inventory, workflow, ledger, and reporting data | Read balances, receipts, shipments, audit/report views |
| O1 — Operational execution | Prepare or advance a workflow without committing inventory/accounting state | Create, assign, start, confirm, package, seal |
| C1 — Inventory commitment | Commit or reserve inventory and perform irreversible physical/accounting handoff | Reserve, inbound post, direct inventory post, ship, transfer dispatch/receive |
| A1 — Independent approval/reversal | Independently approve, reject, post variance, or reverse a committed operation | Stock-count approval, adjustment approval/rejection/post, controlled reversal |

Risk classes are not a substitute for exact permission checks. In particular, O1 does not imply that all execution actions are low impact, and C1/A1 must never be delegated based only on a role name.

## 4. Initial policy defaults

- R1: no fresh MFA requirement; reads remain tenant/resource scoped and subject to data-access audit policy.
- G1/M1: fresh MFA is required for privileged membership/role governance and high-impact master-data lifecycle changes; ordinary reads are not elevated.
- O1: fresh MFA is not universally required; workflow-specific SoD and state checks remain mandatory.
- C1/A1: fresh MFA is required within the existing five-minute freshness window before the protected action.
- Every mutation is audited. C1/A1 always require durable actor, tenant, operation reference, target, outcome, and UTC timestamp evidence.
- Tenant custom-role grantability is an explicit allowlist and must remain separate from risk class. High-risk permissions are not automatically tenant-delegable.
- Platform-only permissions are excluded from this registry and must never appear in tenant role grants.

The implementation must preserve the existing five-minute platform MFA policy and must not silently change tenant authentication semantics. If tenant MFA is not yet available for a protected action, the action must fail closed rather than treating a platform MFA claim as tenant MFA.

## 5. Separation-of-duties vocabulary

The registry may label a permission with a SoD group, but metadata alone does not enforce SoD. Enforcement must compare persisted actor membership/user identity from trusted workflow records, never client-supplied actor IDs.

Initial groups to audit and enforce where the workflow requires dual control:

- `INBOUND_RECEIPT`: receipt preparer versus receipt poster.
- `OUTBOUND_SHIPMENT`: shipment preparer/releaser versus final shipping actor, according to the approved control point.
- `INTER_WAREHOUSE_TRANSFER`: source dispatcher versus destination receiver.
- `STOCK_COUNT`: count recorder/submitting actor versus independent reviewer/approver.
- `INVENTORY_ADJUSTMENT`: adjustment submitter versus approver/poster.
- `PLATFORM_PRIVILEGED_DELEGATION`: role/grant requester versus independent approver.

A SoD group is a review/enforcement key, not a blanket prohibition on assigning multiple roles. Small warehouses may combine operational duties where no independent control is required; critical commit and approval boundaries must remain independent.

## 6. Required implementation and test gates

1. Enumerate every tenant WMS permission from the complete migration history and compare it with the registry. Missing, duplicate, unknown, or unclassified codes fail tests.
2. Verify each registry module and description against the persisted catalogue using MySQL integration tests.
3. Verify risk class, MFA, audit, tenant-assignability, and SoD metadata for every entry.
4. Keep catalogue visibility, custom-role delegation, system-role templates, and risk metadata as separate policy dimensions.
5. Add negative tests proving that a high-risk permission cannot be obtained through custom-role creation, role replacement, self-assignment, or platform/tenant principal confusion.
6. Add action-level tests for fresh MFA and persisted actor separation; do not treat a registry unit test as proof of endpoint enforcement.
7. Preserve existing migrations V1–V30. A metadata-only registry does not justify rewriting historical migrations or mutating a user database.

## 7. Out of scope

- No bootstrap operator, owner account, role grant, or credential provisioning.
- No wildcard or superuser permission.
- No support impersonation/session or access to tenant warehouse transactions.
- No weakening/removal of existing tests or security checks.
- No change to tenant data, subscription entitlements, or production database state.
