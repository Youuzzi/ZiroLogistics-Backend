# Tenant Role Templates and Permission Governance Policy

**Status:** Proposed policy baseline; not yet implemented or approved as production grant data.  
**Reviewed source baseline:** `cc1c489810e9551273d6c45397a3ef86150bd1b2`  
**Related evidence:** `docs/audits/TENANT_RBAC_PERMISSION_CATALOGUE_AUDIT.md`, `docs/architecture/WMS_ROLE_PERMISSION_MANAGEMENT_DESIGN.md`, `docs/security/TENANT_ISOLATION_AND_AUTHORIZATION.md`  
**Scope:** Tenant-scoped role templates, permission risk classes, delegation ceilings, separation of duties, and bootstrap/provisioning parity.

## 1. Purpose

Define a consistent least-privilege role policy for the WMS permission catalogue. The policy must be shared by first-install bootstrap, repeatable tenant provisioning, role catalogue responses, role permission changes, and membership-role assignment.

The permission catalogue audit identified separate bootstrap and provisioning owner allowlists, historical migration backfills, and a restricted hard-coded delegation catalogue. These paths must converge on one canonical policy. This document is the proposed policy contract; it does not claim that the role templates or grants are already implemented.

## 2. Authorization model

A protected WMS operation is allowed only when all required dimensions pass:

- The authenticated user and selected membership resolve to an active tenant context.
- The membership has an active role that grants the operation's permission.
- The tenant's product entitlement and lifecycle state permit the capability.
- The resource belongs to the resolved tenant and satisfies the operation's state/ownership constraints.
- Any separation-of-duties rule for the operation is satisfied.

RBAC and product entitlement remain separate. A role does not enable a subscription module, and a subscription does not grant a user permission.

A membership may have multiple active roles; effective permissions are the union of permissions from all active assigned roles, still bounded by tenant/resource scope and lifecycle checks. This supports combining an administrative role with a narrowly scoped operational role without turning the owner role into an all-powerful operational role.

## 3. Permission risk classes

Every global permission code must be classified once in a canonical server-side definition.

| Class | Meaning | Examples |
|---|---|---|
| G1 — Tenant governance | Tenant identity/profile, membership invitations, role and assignment administration | `WMS_TENANT_PROFILE_UPDATE`, `WMS_IDENTITY_MEMBERSHIP_INVITE`, role/membership governance |
| M1 — Master data mutation | Warehouse, location, item, category, identifier, UOM, lot and serial administration | `WMS_WAREHOUSE_CREATE`, `WMS_ITEM_UPDATE`, `WMS_SERIAL_MANAGE` |
| R1 — Operational read | Read operational documents, inventory, ledger, tasks, reports | module `*_READ`, `WMS_INVENTORY_READ`, `WMS_OPERATIONAL_REPORT_READ` |
| O1 — Operational execution | Create, update, assign, start, confirm, package, seal, dispatch, receive | inbound, putaway, picking, packing, transfer, replenishment execution |
| C1 — Inventory commitment | Reserve, post, ship, or otherwise commit inventory/accounting state | `WMS_INVENTORY_RESERVE`, `WMS_INVENTORY_POST`, inbound receipt post, shipping |
| A1 — Independent approval/reversal | Approve/reject/post variances, reverse committed operations, approve stock-count outcomes | stock-count approve, adjustment approve/reject/post, replenishment confirmation reverse |
| P1 — Platform-only | Platform operator, tenant lifecycle, support grant, billing/security control-plane actions | Not part of the tenant WMS permission catalogue |

Risk classes are metadata for policy and review. They must not replace explicit per-operation authorization checks.

## 4. Proposed role templates

The following templates are the recommended default tenant role set. They are policy bundles, not necessarily persisted system roles today. A tenant may create custom roles only within the grantability rules in section 6.

### 4.1 Tenant Owner — governance and oversight

**Purpose:** Own the tenant relationship, tenant profile, membership access governance, and business oversight.

Proposed grants:
- G1: `WMS_IDENTITY_MEMBERSHIP_INVITE`, `WMS_TENANT_PROFILE_UPDATE`, `WMS_ROLE_READ`, `WMS_ROLE_CREATE`, `WMS_ROLE_UPDATE`, `WMS_ROLE_DEACTIVATE`, `WMS_PERMISSION_CATALOG_READ`, `WMS_ROLE_PERMISSION_MANAGE`, `WMS_MEMBERSHIP_ROLE_READ`, `WMS_MEMBERSHIP_ROLE_MANAGE`.
- R1: all tenant WMS module read permissions, including warehouse/location, item/master data, lot/serial, inventory/ledger, inbound, putaway, outbound, picking, packing, shipping, transfer, stock count, adjustment, replenishment, and operational reports.
- M1: warehouse/location and item/master-data administration only where the owner explicitly needs to maintain the tenant's operating model; these are not required for the identity of ownership and may be withheld in a stricter tenant policy.

Explicit exclusions by default:
- O1 workflow execution permissions.
- C1 inventory reservation, posting, or shipping commitment permissions.
- A1 approval, rejection, posting of adjustments, or reversal permissions.
- P1 platform permissions.

Rationale: tenant governance and business oversight must not automatically make the owner the person who performs and approves the same inventory operation. An owner who also works in warehouse operations receives a separate operational role assignment.

### 4.2 Tenant Administrator — delegated administration

**Purpose:** Maintain day-to-day tenant configuration without platform access or automatic high-risk inventory authority.

Proposed grant groups:
- Selected G1 administrative capabilities only when explicitly delegated. The default custom-role grant ceiling excludes permission catalogue mutation, role permission management, membership-role assignment, and owner lifecycle.
- M1 warehouse/location and item/master-data CRUD/status management.
- R1 tenant operational read permissions needed to support administration.
- No C1 or A1 by default.

The Tenant Administrator template should be implemented as a controlled, server-defined template or a custom role generated from the canonical grantable set. It must not be a disguised Tenant Owner or a route to grant governance permissions beyond the caller's authority.

### 4.3 Warehouse Supervisor

**Purpose:** Coordinate warehouse execution and task assignment.

Proposed grants:
- R1 warehouse/location, inventory, inbound, putaway, outbound, picking, packing, transfer, stock count, and replenishment reads.
- O1 putaway assignment; picking assignment; packing assignment; replenishment task assignment/release where operationally required.
- No inventory post, stock-count approval, adjustment approval/post, or replenishment reversal by default.

### 4.4 Inbound Operator

**Purpose:** Receive goods and prepare putaway work.

Proposed grants:
- `WMS_INBOUND_RECEIPT_READ`, `WMS_INBOUND_RECEIPT_CREATE`, `WMS_INBOUND_RECEIPT_UPDATE`.
- `WMS_PUTAWAY_READ`, `WMS_PUTAWAY_CREATE`, `WMS_PUTAWAY_START`, `WMS_PUTAWAY_CONFIRM`.
- Required master-data reads: item, UOM catalogue, warehouse/location, lot and serial reads.
- No `WMS_INBOUND_RECEIPT_POST` or general `WMS_INVENTORY_POST` by default.

### 4.5 Inbound Controller

**Purpose:** Independently validate and post completed receiving transactions.

Proposed grants:
- Inbound receipt read and post.
- Inventory and inventory-ledger read.
- Required item, warehouse/location, lot and serial reads.
- No authority to change receiving quantities after submission unless a separately audited correction workflow is used.
- A user who prepared/recorded the receipt must not be the independent poster when tenant policy requires dual control.

### 4.6 Outbound Operator

**Purpose:** Prepare, allocate, pick, and pack outbound shipments.

Proposed grants:
- Outbound shipment read/create/update/allocate.
- Inventory reserve.
- Picking read/create/assign/start/confirm.
- Packing read/create/ready/assign/start/package/seal/complete.
- Required item, inventory, warehouse/location, lot and serial reads.
- No `WMS_OUTBOUND_SHIPMENT_RELEASE` or `WMS_SHIPPING_SHIP` by default.

### 4.7 Shipping Clerk

**Purpose:** Perform the final shipping handoff after packing is complete.

Proposed grants:
- `WMS_SHIPPING_READ`, `WMS_SHIPPING_SHIP`.
- Outbound shipment read and packing read as needed for dispatch validation.
- No shipment creation, allocation, inventory adjustment, or posting rights.

### 4.8 Inventory Controller

**Purpose:** Control stock integrity, stock counts, and adjustment submissions.

Proposed grants:
- Inventory read and ledger read; lot/serial read and manage.
- Stock count read/create/plan/start/record/submit.
- Inventory adjustment read/create/submit.
- Replenishment read, rule management, task create/release/assign/start/confirm/cancel.
- No stock-count approve, adjustment approve/reject/post, or replenishment confirmation reverse by default.

### 4.9 Inventory Approver

**Purpose:** Independently review and decide inventory variance and adjustment requests.

Proposed grants:
- Stock count read/review/approve/cancel.
- Inventory adjustment read/approve/reject/post.
- Inventory read and ledger read.
- Replenishment read only unless a separately reviewed approval workflow is added.
- Cannot approve, reject, or post an operation submitted by the same actor. The check must use persisted actor membership/user identity, not a client-supplied reference.

### 4.10 Transfer Dispatcher and Transfer Receiver

Split transfer duties into two templates where tenant size and operating model justify it.

**Transfer Dispatcher**
- Transfer read/create/release/dispatch.
- Source warehouse/location, inventory, lot and serial reads.
- No transfer receive by default.

**Transfer Receiver**
- Transfer read/receive.
- Destination warehouse/location, inventory, lot and serial reads.
- No transfer create/release/dispatch by default.

If a small tenant intentionally combines dispatch and receive duties, that must be an explicit tenant policy decision and remain visible in audit evidence; it must not happen because the default role grants both sides automatically.

### 4.11 Replenishment Operator

**Purpose:** Execute replenishment work without controlling reversal of completed inventory movement.

Proposed grants:
- Replenishment read and task create/release/assign/start/confirm/cancel.
- Inventory, warehouse/location, item and lot/serial reads as required.
- No `WMS_REPLENISHMENT_CONFIRMATION_REVERSE` by default.

### 4.12 Reporting / Auditor

**Purpose:** Read operational performance and permitted evidence without mutation rights.

Proposed grants:
- `WMS_OPERATIONAL_REPORT_READ` and explicitly approved module read permissions.
- No G1 mutation, M1, O1, C1, or A1 permissions.

The audit role must not imply access to platform-wide logs or another tenant's data.

## 5. Permission-to-role matrix

The matrix below is the initial default policy proposal. A check mark means the role template may include the permission; it does not mean the permission is currently granted in the repository.

| Permission family | Owner | Tenant Admin | Warehouse Supervisor | Inbound Operator | Inbound Controller | Outbound Operator | Shipping Clerk | Inventory Controller | Inventory Approver | Transfer Dispatcher | Transfer Receiver | Replenishment Operator | Auditor |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Tenant profile / invite | ✓ | delegated | — | — | — | — | — | — | — | — | — | — | — |
| Role and membership governance | ✓ | restricted | — | — | — | — | — | — | — | — | — | — | — |
| Warehouse/location master data | ✓* | ✓ | read | read | read | read | read | read | read | read | read | read | read |
| Item/category/UOM master data | ✓* | ✓ | read | read | read | read | read | read | read | read | read | read | read |
| Lot/serial management | ✓* | restricted | read | read | read | read | — | manage | read | read | read | read | read |
| Inventory/ledger read | ✓ | ✓ | ✓ | read | ✓ | ✓ | read | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Inbound receipt create/update | — | — | — | ✓ | — | — | — | — | — | — | — | — | — |
| Inbound receipt post | — | — | — | — | ✓ | — | — | — | — | — | — | — | — |
| Putaway create/start/confirm | — | — | assign | ✓ | — | — | — | — | — | — | — | — | — |
| Outbound shipment create/update/allocate | — | — | — | — | — | ✓ | — | — | — | — | — | — | — |
| Inventory reserve | — | — | — | — | — | ✓ | — | — | — | — | — | — | — |
| Picking / packing execution | — | — | assign | — | — | ✓ | read | — | — | — | — | — | — |
| Shipment release | — | — | policy-specific | — | — | — | — | — | — | — | — | — | — |
| Shipping commit | — | — | — | — | — | — | ✓ | — | — | — | — | — | — |
| Stock count prepare/record/submit | — | — | read/assign | — | — | — | — | ✓ | — | — | — | — | — |
| Stock count approve | — | — | — | — | — | — | — | — | ✓ | — | — | — | — |
| Adjustment create/submit | — | — | — | — | — | — | — | ✓ | — | — | — | — | — |
| Adjustment approve/reject/post | — | — | — | — | — | — | — | — | ✓ | — | — | — | — |
| Transfer create/release/dispatch | — | — | read/assign | — | — | — | — | — | — | ✓ | — | — | — |
| Transfer receive | — | — | read/assign | — | — | — | — | — | — | — | ✓ | — | — |
| Replenishment execution | — | — | assign | — | — | — | — | ✓ | — | — | — | ✓ | — |
| Replenishment reversal | — | — | — | — | — | — | — | — | — | — | — | — | — |
| Operational reporting | ✓ | ✓ | ✓ | scoped | scoped | scoped | scoped | ✓ | ✓ | scoped | scoped | scoped | ✓ |

`✓*` means tenant governance may include master-data administration if the owner is intended to maintain the tenant's operating model; a stricter owner template can be read-only. `restricted` means only the exact permissions in the approved delegable set, never all G1 or M1 permissions by role name. `scoped` means the read permission may be included only when needed for the role's workflow.

The matrix is intentionally conservative for C1 and A1. Shipment release and other operational commitment actions must receive a dedicated policy review before default grants are finalized.

## 6. Canonical policy and delegation contract

Implement one immutable server-side policy definition with, at minimum, these attributes per permission code:

- code, module, display description;
- risk class;
- whether tenant roles may receive it;
- whether it is visible in a caller's permission catalogue;
- whether it is delegable by a caller holding it;
- whether it is reserved for protected system templates;
- whether it is approval/commit/reversal-sensitive;
- applicable separation-of-duties rule identifier.

The policy must expose distinct operations:
1. **Catalogue:** globally defined permission codes.
2. **Visible to caller:** codes the caller may inspect.
3. **Grantable by caller:** codes within the caller's authority and tenant policy.
4. **Template grants:** exact permission set for each server-defined role template.

Grant validation must be fail-closed:
- Requested code must exist and be enabled.
- Code must be tenant-grantable.
- Caller must hold the required delegation authority.
- Code must be allowed for the target role template/type.
- Target role and membership must belong to the resolved tenant.
- Protected system-role grants cannot be changed through custom-role endpoints.
- No partial grant writes if any code fails validation.

The current caller-authority ceiling (a caller cannot grant a permission they do not hold) should be retained as a minimum rule, not replaced by a role-name shortcut. Any exception for assigning a server-defined template must be explicit, audited, and restricted to a protected template identifier.

## 7. Separation-of-duties invariants

At minimum, enforce the following at the application/domain layer and verify with MySQL integration tests:

1. A user cannot approve, reject, post, or reverse their own submitted inventory adjustment.
2. A user cannot approve their own stock-count variance submission.
3. Inbound receipt posting is distinct from receipt entry/update when tenant dual control is enabled.
4. Shipping commit is distinct from shipment preparation/allocation by default.
5. Transfer dispatch and transfer receipt are separate roles by default; combined duty requires explicit tenant policy.
6. Replenishment confirmation reversal is separate from normal task confirmation.
7. Role/permission governance cannot be delegated through arbitrary custom roles.
8. No user can remove/deactivate the last effective Tenant Owner or leave the tenant without an owner.
9. Self-assignment cannot be used to escalate privileges. First-owner bootstrap and controlled owner succession require explicit, audited paths.
10. Audit actor identity must resolve to persisted user/membership identity; a free-form operator reference is attribution metadata, not authentication.

A permission split alone is not sufficient. The service must compare the persisted submitting actor with the persisted approving actor and enforce the transition invariant transactionally.

## 8. Bootstrap and provisioning parity

The exact same canonical policy must supply role grants for:
- first-install bootstrap;
- repeatable tenant provisioning;
- any future tenant migration/provisioning repair command;
- system-defined role template assignment.

No Flyway backfill should be treated as the default for tenants created after migrations have already run. Historical migrations remain immutable. If system role templates are stored durably, add a new additive migration and ensure it is compatible with both fresh and existing WMS databases.

Bootstrap and provisioning must produce the same initial owner role permissions for the same policy version. Tests must compare the resulting persisted role-permission rows, not only compare Java constants.

## 9. Required verification

### Policy/catalogue tests
- Every migration-seeded permission is represented exactly once in the canonical policy.
- No unknown policy code is absent from the persisted permission catalogue.
- Catalogue visibility, grantability, and template grants are distinct and independently tested.
- No platform-only permission is exposed by tenant WMS APIs.

### Provisioning parity tests
- Fresh-install bootstrap and repeatable provisioning produce identical owner grant sets.
- Provisioning a tenant after all migrations have run still assigns the complete approved role template.
- Re-running provisioning is idempotent and does not widen or erase customized tenant roles.

### Authorization and escalation tests
- A caller cannot grant a permission they do not hold.
- A caller cannot grant a non-delegable permission to a custom role.
- A tenant cannot assign another tenant's role or membership.
- Inactive users, memberships, tenants, and roles deny access.
- Protected system roles cannot be modified through custom-role APIs.
- Last-owner removal, self-escalation, and self-approval are denied atomically.

### Workflow tests
- Each high-risk approval/post/reversal operation has negative tests for same-actor submission and approval.
- Each operational role is tested against at least one allowed and one denied route/use case per permission family.
- Audit failure rolls back permission and membership-role mutations.

Run these suites with MySQL 8.4 Testcontainers and include HTTP authorization-boundary coverage. Unit tests over constants alone are insufficient.

## 10. Implementation order

1. Complete and merge the source permission catalogue audit.
2. Approve this role-template matrix and risk classifications.
3. Implement the canonical permission policy source and remove duplicated hard-coded lists.
4. Align first-install bootstrap and repeatable provisioning to the same template.
5. Align catalogue visibility and delegation validation to policy metadata.
6. Add additive migration only if durable role-template metadata is required.
7. Add parity, escalation, separation-of-duties, and tenant-isolation tests.
8. Re-run full regression, DAST, CodeQL, and migration verification before enabling the policy for tenant bootstrap.

## 11. Current implementation status

| Capability | Status |
|---|---|
| Global WMS permission catalogue | Seeded by Flyway V5 and V7–V26 |
| DB-backed effective permission resolution | Implemented |
| Multiple active role permission union | Implemented in membership-access query/service |
| Role CRUD and protected system-role handling | Partially implemented |
| Caller-authority check for permission replacement | Implemented with a restricted hard-coded allowlist |
| Canonical permission risk metadata | Not implemented |
| Canonical catalogue/visible/grantable/template policy | Not implemented |
| Reviewed default role templates | Proposed in this document only |
| Bootstrap/provisioning grant parity | Not achieved |
| Cross-workflow separation-of-duties enforcement | Not certified |
| Tenant entitlement enforcement | Separate future work; not implemented by this policy |

No permission grants, Flyway migrations, tenant records, credentials, Docker resources, or databases were changed by this policy design document.
