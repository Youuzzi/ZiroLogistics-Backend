# Stage 16 — Custom Tenant Role Delegation Audit

**Status:** Evidence-backed current-state review; not a certification of the complete RBAC surface.

**Scope:** Tenant custom-role permission visibility, grantability, caller delegation authority, system-role protection, membership-role assignment, and the associated tests. Platform operator permissions are outside this tenant policy.

## 1. Sources of truth inspected

- `src/main/java/com/zirocraft/zirologistics/wms/authorization/domain/WmsTenantPermissionPolicy.java`
- `src/main/java/com/zirocraft/zirologistics/wms/authorization/application/WmsRoleManagementService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/authorization/application/WmsMembershipAccessService.java`
- `src/test/java/com/zirocraft/zirologistics/wms/authorization/WmsRoleManagementServiceTest.java`
- `docs/audits/WMS_STAGE_16_TENANT_RBAC_CURRENT_STATE_MATRIX_AUDIT.md`

The canonical policy owns separate methods for tenant role-manager catalogue visibility, custom-role grantability, Tenant Owner baseline grants, self-assignment-restricted system roles, and the 13 operational system-role templates. The methods are separate policy concepts even where their current values happen to be equal.

## 2. Three distinct authorization decisions

| Decision | Current source | Current behavior |
|---|---|---|
| Catalogue visibility | `catalogueVisiblePermissionCodes()` | Only the explicit 20-code tenant role-manager catalogue is returned. |
| Grantability to custom roles | `delegablePermissionCodes()` | Only the explicit 20-code custom-role allowlist can be requested. |
| Caller delegation authority | Live `WmsMembershipAccessService.resolve(...)` result | The acting membership must currently hold every requested permission, in addition to the allowlist check. |

The catalogue-visible and custom-role-delegable sets currently contain the same 20 codes. This is a current implementation fact, not a policy invariant: they must remain independently reviewable and must not be coupled by assuming that visibility implies grantability.

### Current explicit 20-code set

- `WMS_ROLE_READ`
- `WMS_ROLE_CREATE`
- `WMS_ROLE_UPDATE`
- `WMS_ROLE_DEACTIVATE`
- `WMS_PERMISSION_CATALOG_READ`
- `WMS_MEMBERSHIP_ROLE_READ`
- `WMS_WAREHOUSE_READ`
- `WMS_WAREHOUSE_CREATE`
- `WMS_WAREHOUSE_UPDATE`
- `WMS_LOCATION_READ`
- `WMS_LOCATION_CREATE`
- `WMS_LOCATION_UPDATE`
- `WMS_ITEM_READ`
- `WMS_ITEM_CREATE`
- `WMS_ITEM_UPDATE`
- `WMS_ITEM_CATEGORY_READ`
- `WMS_ITEM_CATEGORY_MANAGE`
- `WMS_UOM_CATALOG_READ`
- `WMS_INVENTORY_READ`
- `WMS_INVENTORY_LEDGER_READ`

This is intentionally not the 94-entry permission-risk registry and not the full permission catalogue. Unknown or newly introduced permission codes are not implicitly delegable.

## 3. Runtime checks in role permission replacement

`WmsRoleManagementService.replaceRolePermissions` performs the following checks inside a transaction:

1. Resolve the current membership from persisted state and verify that principal tenant, membership, user and public identifiers match the resolved identity.
2. Require the exact `WMS_ROLE_PERMISSION_MANAGE` permission.
3. Lock the active tenant row before role mutation.
4. Resolve the target role using both tenant ID and role public ID.
5. Reject system-role grant replacement.
6. Enforce the expected role version.
7. Reject null collections, more than 100 requested entries, null codes and duplicate codes.
8. Normalize requested codes using trim and uppercase.
9. Require every requested code to be in the explicit custom-role allowlist.
10. Require the acting membership to hold every requested code at the time of the request.
11. Verify each permission code exists in the permission catalogue.
12. Replace the role grants, increment the role version, and write the tenant audit event in the same transaction.

The allowlist and actor-authority checks are conjunctive. Possessing a sensitive permission does not bypass the allowlist; appearing in the allowlist does not let an actor delegate a permission they do not hold.

## 4. Related role and membership protections

- Custom role creation rejects codes reserved for the 13 server-managed system-role templates.
- System-role permissions cannot be replaced through the custom-role endpoint.
- System roles cannot be renamed through the custom-role update path or deactivated.
- The `TENANT_OWNER` role cannot be deactivated.
- The last active Tenant Owner assignment cannot be removed.
- Six high-risk system roles are explicitly restricted from self-assignment: `SYS_INBOUND_CONTROLLER`, `SYS_OUTBOUND_CONTROLLER`, `SYS_SHIPPING_CLERK`, `SYS_INVENTORY_APPROVER`, `SYS_TRANSFER_DISPATCHER`, and `SYS_TRANSFER_RECEIVER`.
- Role and membership lookups are tenant-scoped; role joins require the role tenant to match the membership-role tenant.
- Role create/update/deactivate, permission replacement and membership-role assign/remove have tenant audit event types.

The six self-assignment restrictions are not the complete SoD policy. Workflow services must still compare persisted actors at the point of execution/approval.

## 5. Existing test evidence

`WmsRoleManagementServiceTest` currently covers:

- Exact dedicated permission checks for role read/create/update/deactivate, catalogue read, role permission management, membership-role read and assignment/removal.
- Denial when the authenticated principal tenant does not match persisted membership context, before JDBC access.
- Live permission re-resolution so revocation applies to an existing principal.
- Reserved system-role code rejection.
- High-risk system-role self-assignment denial.
- Denial when the actor does not hold a requested permission.
- Denial of inventory posting delegation even when the actor holds that permission, because it is outside the allowlist.
- Tenant-scoped role lookup and denial for a foreign-tenant role.

These are service-level unit tests with mocked persistence. They do not alone prove MySQL transaction rollback, durable audit evidence, concurrent role mutation, or all negative combinations against the real schema.

## 6. Findings and bounded gaps

1. **The current allowlist is deliberately narrow.** It includes governance, selected master-data writes, and inventory reads. It excludes operational commit and approval permissions such as inbound posting, inventory posting, shipping, transfer dispatch/receive, stock-count approval and adjustment approval/posting.
2. **Visibility and grantability are currently equal in value.** Their separate methods are useful, but a future change must review each decision independently.
3. **Caller authority is a separate live check.** It must not be replaced by a UI filter, a stale JWT permission snapshot, or catalogue membership.
4. **The risk registry does not automatically grant permission.** Risk class, MFA, audit and SoD metadata should inform policy review; they must not silently expand the allowlist.
5. **The current role-management unit tests are not a complete persistence certification.** MySQL-backed tests should prove role grant replacement, audit atomicity, rollback on invalid/unknown grants, revocation, role deactivation, and concurrent version conflicts.
6. **Assignment governance needs its own coverage.** Test multi-role assignment/removal, self-assignment restrictions for each protected role, last-owner protection under concurrency, inactive membership/role rejection, and tenant mismatch.
7. **The role-manager catalogue is not a universal WMS permission browser.** The current 20-code subset is the supported delegable surface. Expanding it requires explicit risk review and negative tests, not a blanket copy of the full registry.

## 7. Acceptance criteria before RBAC delegation sign-off

- Visibility, grantability and caller authority remain separate policy decisions.
- Every allowlisted code is mapped to its risk metadata and approved business purpose.
- Every C1/A1 or SoD-bound permission remains non-delegable unless a separately reviewed policy and enforcement proof explicitly authorize it.
- MySQL tests prove atomic replacement, rollback, audit durability and optimistic concurrency.
- MySQL tests prove current membership permissions are re-evaluated and revoked grants stop taking effect immediately.
- Assignment tests prove tenant scope, self-assignment controls, system-role protection and last-owner safety.
- The full required CI, CodeQL, dependency scan and authenticated DAST pass on the exact reviewed head.

## 8. References

- NIST CSRC, Role Based Access Control FAQs: https://csrc.nist.gov/projects/role-based-access-control/faqs
- NIST CSRC, Separation of Duty: https://csrc.nist.gov/glossary/term/separation_of_duty
- AWS SaaS Architecture Fundamentals, Tenant isolation: https://docs.aws.amazon.com/whitepapers/latest/saas-architecture-fundamentals/tenant-isolation.html

---
This audit records the inspected implementation and tests; it is not a security, compliance, or full RBAC certification.
