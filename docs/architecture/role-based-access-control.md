# Tenant Role-Based Access Control

**Status:** Current tenant RBAC foundation implemented; permission-risk metadata, tenant entitlement enforcement, and universal cross-workflow separation-of-duties coverage remain incomplete.
**Canonical policy:** [Tenant Role Templates and Permission Governance Policy](tenant-role-templates-and-permission-governance-policy.md).
**Implementation source:** `WmsTenantPermissionPolicy`, persisted permission/role repositories, membership-access resolution, and MySQL persisted-grant integration tests.
**Scope:** Tenant-scoped roles, permission grants, membership-role assignments, delegation, and operational separation of duties.

## 1. Authorization boundary

Tenant RBAC determines whether an authenticated member may perform an action inside the currently selected tenant. It is separate from the Platform Administration control plane and from SaaS product entitlement.

A protected operation must satisfy all applicable conditions:

1. The authenticated global user and selected membership resolve to an active tenant context.
2. The membership has active tenant role assignments and the required persisted permission.
3. The target resource belongs to the resolved tenant.
4. The workflow state, business invariants, and actor constraints permit the operation.
5. Any required approval or separation-of-duties rule is satisfied.
6. A tenant entitlement check is applied when that separate capability is implemented.

Client-supplied tenant IDs, role names, permission lists, or JWT permission snapshots are not authorization evidence.

## 2. Implemented policy

The current implementation includes:

- Tenant-scoped roles and membership-role assignments.
- Database-backed effective-permission resolution from current persisted state.
- A canonical `WmsTenantPermissionPolicy` shared by first-install bootstrap and repeatable tenant provisioning.
- A 30-code Tenant Owner governance and read-only operational baseline; the Owner is not automatically granted warehouse execution or inventory-posting rights.
- Thirteen immutable operational tenant system-role templates provisioned for new and existing tenants through the canonical policy and V28 reconciliation.
- Separate catalogue visibility, custom-role delegation, Owner grants, protected role-template grants, and restricted self-assignment rules.
- Caller-effective-permission and delegation-ceiling checks for role permission changes.
- Protected system-role handling and persisted target-user identity checks for high-risk role self-assignment.
- Workflow-specific permission checks for internal inbound, putaway, picking, shipping, transfer, replenishment, and adjustment operations. The generic `WMS_INVENTORY_POST` permission is reserved for the direct inventory-posting API.

Exact permission codes and role grants must be read from the canonical Java policy and its persisted-grant tests. The illustrative role matrix in older design material is not an authoritative serialization of current grants.

## 3. Core invariants

1. Every tenant role belongs to exactly one tenant.
2. Membership-role assignments cannot cross tenant boundaries.
3. Tenant context comes from the authenticated principal and persisted membership, never a caller-selected tenant identifier.
4. Permission definitions are server-owned and version-controlled through migrations.
5. Unknown, inactive, platform-only, or non-delegable permissions are rejected.
6. A caller cannot grant permissions outside the caller's effective delegation ceiling.
7. Protected system-role grants cannot be modified through ordinary custom-role APIs.
8. Tenant Owner does not imply Platform Administrator or unrestricted operational posting authority.
9. Role and membership changes are transactional and audited.
10. The last effective Tenant Owner path must not be removed without an atomic, authorized replacement.
11. Cross-tenant object references fail closed and must not disclose protected resource existence.
12. Permission changes are resolved from persisted state; stale token snapshots must not preserve revoked grants.

## 4. Role and permission model

The current role catalogue contains the Tenant Owner baseline and thirteen operational system-role templates. These templates are server-defined and protected; they are not interchangeable with arbitrary custom roles.

A membership may hold multiple active roles. Effective permissions are resolved from the current persisted assignments and grants. This permits a tenant to combine governance and narrowly scoped operational responsibilities without making ownership itself an unrestricted warehouse role.

The canonical policy separates:
- permissions visible in a caller's catalogue;
- permissions the caller may delegate;
- grants belonging to protected system-role templates;
- the Tenant Owner baseline; and
- restricted system roles that cannot be self-assigned through ordinary role-management flows.

Do not infer grantability from a permission-code prefix or from a role's display name.

## 5. Remaining policy and assurance gaps

The following are not complete merely because the role templates and permission grants exist:

- Machine-readable risk metadata for every permission is not yet implemented as a universal policy registry.
- Universal cross-workflow separation-of-duties enforcement is incomplete. High-risk system-role self-assignment and selected stock-count/adjustment actor-separation paths are covered, but inbound, shipping, and transfer actor separation are not universally certified.
- Tenant entitlement/plan enforcement remains a separate future capability and is not implemented by tenant RBAC.
- Complete route-to-query and cross-tenant verification remains open; selected source reviews and MySQL tests are not a certification of every route, nested identifier, worker, or command.
- A full permission catalogue-to-enforcement-to-test trace remains a follow-up.

## 6. Operational and security requirements

For each protected operation, resolve the authenticated principal and active membership, load current persisted permissions, enforce resource ownership and workflow state, apply approval rules, then commit the business mutation and required audit event atomically.

Role-management requests must validate the complete requested permission set before writing. A failed validation or audit write must not leave partial grants. System-role assignment, owner succession, and other multi-row invariants require transaction and concurrency tests, not UI-only restrictions.

Platform operator permissions belong exclusively to the separate platform control plane. Tenant roles must never grant platform administration, support-grant, or billing authority.

## 7. Verification references

- [Canonical tenant role templates and permission governance](tenant-role-templates-and-permission-governance-policy.md)
- [Tenant RBAC permission catalogue and provisioning audit](../audits/tenant-rbac-permission-catalogue-audit.md)
- [Tenant isolation and authorization baseline](../security/tenant-isolation-and-authorization.md)
- [Tenant isolation route-to-query verification plan](../audits/tenant-isolation-route-to-query-verification-plan.md)
- [WMS API v1 compatibility reference](../api/wms-api-v1-reference.md)

The implementation status above reflects the documented current checkpoint. Revalidate source and tests before treating any individual permission or workflow as production-certified.
