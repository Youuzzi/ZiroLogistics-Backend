# Tenant Role-Based Access Control

**Status:** Target authorization policy; current implementation gaps are recorded explicitly below. This document does not claim that the target role catalogue or permission matrix is implemented.
**Scope:** Tenant-scoped roles, global WMS permission definitions, role grants, membership-role assignments, delegation, and separation of duties.
**Security baseline:** Deny by default; permissions are resolved from persisted tenant membership state.

## 1. Purpose and authority model

Tenant RBAC answers whether an authenticated member may perform a specific WMS action within the selected tenant. It does not establish platform authority and does not establish whether the tenant has purchased or enabled a product capability.

The effective decision for a request is the intersection of:
1. an authenticated active global user;
2. an active membership in the selected active tenant;
3. one or more active tenant roles assigned to that membership;
4. a permission grant persisted for those roles;
5. resource and workflow state constraints enforced by the use case; and
6. an active tenant entitlement for the requested capability, once entitlement enforcement is implemented.

A permission is necessary but not sufficient: business invariants, approval requirements, resource scope, and tenant lifecycle state remain mandatory.

Platform operator permissions belong to the separate platform control plane defined in `platform-administration-and-operations.md`. They must never be inserted into tenant roles or tenant permission grants.

## 2. Verified current implementation and gaps

The repository baseline and current documentation establish these facts:

- Tenant roles and membership-role assignments are tenant-scoped. Effective permissions are resolved from persisted membership and role state; client-supplied permission lists and JWT permission snapshots are not trusted.
- The controlled first-install bootstrap currently grants `WMS_IDENTITY_MEMBERSHIP_INVITE` and `WMS_TENANT_PROFILE_UPDATE` to `TENANT_OWNER`.
- Repeatable tenant provisioning has a different, wider explicit owner allowlist. The two allowlists are not a shared policy source.
- Migrations V9–V26 add permission definitions and backfill grants to owner roles that exist when each migration runs. They cannot grant permissions to roles created later by bootstrap or repeatable provisioning.
- The repeatable provisioning allowlist does not cover all workflow-specific permissions introduced by later migrations, including inbound, putaway, outbound, picking, packing, shipping, transfer, stock count, adjustment, and replenishment actions.
- Role management maintains a narrower delegable-permission set than the complete catalogue. Some workflow permissions therefore cannot be delegated through the current role-management API.
- Existing role-management design documentation is stale: it describes some management permission codes as unseeded proposals even though the audit identified corresponding migration and service implementation.

These are correctness and operability gaps. Do not resolve them by copying the wider provisioning allowlist into bootstrap or by making every catalogue permission delegable. Final grants require risk review and separation-of-duties decisions.

## 3. Core policy invariants

1. Every tenant role belongs to exactly one tenant.
2. A membership can receive roles only within its resolved tenant.
3. The tenant context is derived from the authenticated principal and persisted membership, never from a caller-selected tenant ID.
4. Permission codes are defined by server-owned migrations; tenants cannot create or modify permission definitions.
5. Role grants may contain only active, tenant-delegable permissions approved by policy.
6. A caller may delegate only permissions within the caller's delegation ceiling; having a permission to manage roles does not automatically authorize granting every permission.
7. Platform-only permissions are never tenant-delegable.
8. A tenant role cannot confer an entitlement that the tenant's plan does not include.
9. System roles and protected grants cannot be modified through ordinary custom-role operations.
10. Role assignment and permission changes are transactional, audited, and take effect through database-backed authorization without requiring JWT permission snapshots.
11. No operation may remove, deactivate, or render unusable the final active tenant-owner path unless a replacement owner is established atomically.
12. Cross-tenant object references fail closed and should return a non-disclosing not-found response where appropriate.

## 4. Tenant role model

The following role families define the target operating model. They are policy templates, not a claim that all corresponding system roles are currently seeded.

| Role family | Primary responsibility | Typical authority | Explicit boundary |
|---|---|---|---|
| `TENANT_OWNER` | Legal/customer ownership and initial tenant governance | Tenant profile, owner continuity, appointing tenant administrators, and approved tenant-level settings | No platform control-plane authority; no automatic unrestricted warehouse execution or self-approval |
| `TENANT_ADMIN` | Day-to-day tenant configuration and access administration | Manage permitted tenant users, custom roles, master data and configuration within delegation ceiling | Cannot grant owner-only or restricted approval capabilities |
| `WAREHOUSE_MANAGER` | Warehouse execution oversight | Warehouse operations, work allocation, operational exception review | No tenant identity governance or financial/platform authority by default |
| `INBOUND_OPERATOR` | Receiving and putaway execution | Assigned inbound workflow actions | No approval of own restricted adjustments or unrelated outbound administration |
| `OUTBOUND_OPERATOR` | Picking, packing and shipping execution | Assigned outbound workflow actions | No inventory valuation, tenant administration, or privileged stock adjustments |
| `INVENTORY_CONTROLLER` | Inventory accuracy and control | Stock counts, approved adjustments, lot/serial and inventory status controls | Separate approval required for high-risk adjustments where configured |
| `SUPERVISOR_APPROVER` | Independent review and approval | Review/approve defined exception, adjustment, or workflow actions | Must not approve own originating transaction where separation of duties applies |
| `REPORTING_AUDITOR` | Operational oversight and evidence review | Read-only reporting and audit visibility within tenant scope | No mutation, role assignment, or transaction posting |

Role codes and grants must be finalized against the actual permission catalogue and WMS workflow contracts before they are seeded. Avoid creating a role merely because its label appears in this conceptual model.

## 5. Permission catalogue contract

Permission definitions are global, version-controlled application policy. Each permission must have:

- a stable unique code, using the `WMS_<DOMAIN>_<ACTION>` convention;
- a module/domain and concise human-readable description;
- an action semantic (read, create, update, execute, post, approve, administer);
- a risk classification;
- a resource/tenant scope;
- whether it is assignable to a custom role;
- whether it is eligible for tenant-owner or tenant-admin delegation;
- whether it requires independent approval or separation of duties;
- an audit requirement; and
- lifecycle status (active, deprecated, or retired).

Do not infer policy from a permission-code prefix alone. The server policy must explicitly classify each permission. Unknown, inactive, platform-only, or non-delegable permissions must be rejected before any write.

### 5.1 Risk tiers

| Tier | Meaning | Default delegation policy |
|---|---|---|
| `READ` | Read tenant-scoped data | Delegable when resource scope and data sensitivity permit |
| `STANDARD_WRITE` | Routine master-data or workflow changes | Delegable to operational roles with least privilege |
| `EXECUTION` | Execute or post warehouse workflow steps | Delegable only to relevant operational role families |
| `CONTROLLED` | Stock adjustment, inventory status override, cancellation, or other material exception | Restricted; may require supervisor/approver separation |
| `TENANT_ADMIN` | Tenant identity, role, membership, or tenant configuration administration | Restricted to owner/admin policy; no implicit wildcard |
| `SECURITY_SENSITIVE` | Credential, security, audit-policy, or privileged access controls | Not delegable to ordinary operational roles |
| `PLATFORM_ONLY` | Platform control-plane actions | Never assignable in tenant RBAC |

The catalogue is the authoritative inventory of actual permission codes. This document deliberately does not fabricate a complete code list: the implementation phase must extract every permission from all active Flyway migrations and compare it with enforcement points and tests.

## 6. Owner and administrator policy

The initial owner must be able to establish a functioning tenant without receiving unrestricted operational authority.

The final `TENANT_OWNER` baseline must be one explicit reviewed policy set, shared by:
- first-install bootstrap;
- repeatable tenant provisioning;
- any supported tenant upgrade/backfill procedure;
- role-permission documentation; and
- tests asserting exact grants and prohibited grants.

The policy review must distinguish:
- tenant governance actions the owner may perform directly;
- permissions the owner may delegate to administrators;
- operational actions better assigned to warehouse roles;
- controlled actions requiring independent approval; and
- platform-only capabilities that are never tenant permissions.

Do not treat historical migration backfills as the provisioning policy. New roles are created after the migration chain has already run.

## 7. Delegation and privilege-escalation controls

A permission-management request must validate the complete requested set before writing any grant. The server must enforce all of the following:

1. The requested code exists, is active, and is classified as tenant-delegable.
2. The target role belongs to the caller's resolved tenant and is eligible for grant changes.
3. The caller has the role-management capability and is authorized to delegate every requested permission.
4. No requested permission exceeds the caller's own delegation ceiling.
5. No platform-only, security-sensitive, owner-only, or controlled approval permission is granted through a generic role-management path.
6. The change does not create self-escalation or a circular privilege path.
7. The change does not violate last-owner continuity or separation-of-duties constraints.
8. All changes and audit events commit atomically; validation or audit failure leaves no partial grant set.

The API must expose only permissions the caller is allowed to delegate, not the entire global catalogue. Read visibility and grantability are distinct concepts.

## 8. Separation of duties and sensitive workflows

For controlled operations, separate transaction initiation from approval where business risk requires it. At minimum, implementation must assess:
- inventory adjustments and material stock corrections;
- inventory status changes and damage/write-off;
- stock-count variance approval and posting;
- cancellation or reversal after downstream workflow execution;
- role assignment and privileged permission delegation;
- tenant-owner replacement and last-owner removal.

Where separation of duties is enabled, the same human principal must not initiate and approve the same controlled operation. Enforce this in the application service under transaction, not solely in UI or role labels. Record initiator, approver, reason, operation reference, and before/after state in audit evidence.

Not every WMS action needs dual control. Risk thresholds, amount/quantity limits, warehouse scope, and approval rules must be explicitly defined by the relevant workflow design before implementation.

## 9. Role and membership lifecycle

- System roles are provisioned by controlled application policy and cannot be renamed, deleted, or have protected grants changed by tenant APIs.
- Custom roles may be created, updated, and deactivated only with explicit permissions.
- Prefer deactivation over hard deletion; preserve role and assignment history referenced by audit events.
- Membership and role must be active and belong to the same tenant before assignment.
- Removing the last active owner or deactivating the final owner role assignment is prohibited unless a replacement is committed in the same transaction.
- Role assignment and revocation must be idempotent and concurrency-safe.
- Use row locks or a documented deterministic lock order for aggregate owner invariants; optimistic versioning alone does not protect multi-row invariants.
- A disabled user, membership, tenant, or role must fail authorization even if stale assignment rows remain.

## 10. Authorization evaluation order

For each protected tenant operation:

1. Authenticate the tenant access token using the tenant token profile.
2. Resolve the global user and selected membership from trusted principal identifiers.
3. Verify user, membership, tenant, and assigned roles are active.
4. Resolve effective permissions from persisted role-permission rows.
5. Verify the required action permission.
6. Verify resource ownership and tenant scope in every repository/use-case path.
7. Verify workflow state, quantity/limit rules, and approval/separation-of-duties conditions.
8. Verify tenant entitlement when that layer is implemented.
9. Execute the mutation and append the required audit event in the same transaction.

No platform token or platform role is accepted by this pipeline.

## 11. Bootstrap, provisioning, and migration alignment

The bootstrap service and repeatable provisioning service must consume one shared, version-controlled owner policy component. They must not maintain independent permission constants.

Migration responsibilities are distinct:
- migrations define permission catalogue entries and durable schema constraints;
- application provisioning policy defines grants for newly created tenant roles after all migrations have run;
- any supported upgrade/backfill procedure reconciles existing tenant roles idempotently and records the policy version applied.

A migration backfill may establish grants for roles already present at migration time, but it cannot replace the provisioning policy. Do not edit merged migrations; add new additive migrations only when a durable database invariant or permission definition requires them.

## 12. Audit contract

Every successful role create/update/deactivate, permission grant change, membership-role assignment, owner replacement, and controlled approval must produce an append-only tenant audit event in the same transaction as the business mutation.

Audit evidence should include tenant, actor membership, subject/target, event code, permission/action, operation reference, reason where required, before/after grant or state summary, timestamp in UTC, and request/correlation ID. Never record credentials, bearer tokens, password hashes, MFA secrets, or recovery codes.

Failed authorization attempts belong in the security event channel and must not be represented as successful business audit events.

## 13. Required verification

The implementation is not complete until tests prove:

- exact bootstrap and repeatable-provisioning owner grants are identical and match the approved policy;
- every active permission in every migration is classified in the permission catalogue policy;
- all enforced permissions have a defined owner, risk tier, scope, and delegation rule;
- workflow permissions introduced by later migrations are available to the correct roles where policy permits;
- non-delegable and platform-only permissions cannot be granted through tenant APIs;
- a caller cannot delegate permissions outside their delegation ceiling;
- a custom role cannot escalate its creator or an assigned member;
- system-role protected grants cannot be changed through tenant APIs;
- cross-tenant role, membership, and resource references fail closed;
- the last active owner cannot be removed or disabled without atomic replacement;
- concurrent role/grant/assignment changes preserve tenant and owner invariants;
- audit failure rolls back the associated mutation;
- permission changes take effect through DB-backed resolution without JWT re-issuance;
- controlled workflows enforce independent approval when required; and
- all relevant MySQL 8.4 Testcontainers, HTTP authorization, migration, regression, and security checks pass on the exact commit.

## 14. Implementation sequence

1. Extract the complete permission catalogue from migrations V1–V27 and map each code to its actual enforcement point and tests.
2. Reconcile bootstrap, provisioning, migration backfills, and role-management delegation behavior.
3. Approve the role families, owner baseline, risk tiers, and separation-of-duties matrix.
4. Implement one shared owner-grant policy and a complete server-side permission classification/delegation policy.
5. Add or adjust migrations only for missing durable catalogue/audit constraints; preserve all merged migration history.
6. Add focused unit, HTTP, migration, and MySQL concurrency tests.
7. Update tenant organization, role-management, API, and security documentation to reflect the actual implementation.
8. Run the complete CI and security suite before first-tenant bootstrap is considered.

## 15. Related architecture

- `platform-administration-and-operations.md` — separate platform control-plane identity and authorization.
- `tenant-isolation-and-authorization.md` — tenant boundary and resource isolation.
- `WMS_PERMISSION_CATALOG.md` — canonical extracted permission inventory (to be produced from source and migrations).
- `WMS_ROLE_PERMISSION_MATRIX.md` — approved role-to-permission grants (to be produced after policy review).
- `SAAS_ENTITLEMENT_AND_FEATURE_ACCESS.md` — tenant product capability/plan enforcement, separate from RBAC.
