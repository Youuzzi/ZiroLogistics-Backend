# Tenant RBAC Permission Catalogue and Provisioning Audit

**Status:** Source and MySQL integration-test audit; canonical tenant policy and provisioning parity are implemented. Remaining authorization assurance gaps are listed below.
**Reviewed baseline:** Current `main` at the time of this audit.
**Scope:** Permission catalogue, Tenant Owner baseline, operational system-role templates, bootstrap/provisioning parity, delegation boundaries, and separation-of-duties evidence.
**Assurance:** This audit verifies source structure and the persisted-grant assertions in Testcontainers tests. It does not certify every permission enforcement point or every cross-workflow separation-of-duties path.

## 1. Executive findings

The earlier audit finding that first-install bootstrap granted only two permissions while repeatable provisioning used a different 35-code allowlist is **stale and superseded**.

Current implementation has one canonical policy source:
- `WmsTenantPermissionPolicy` owns the Tenant Owner grant template, the tenant-role catalogue visibility set, the custom-role delegation set, restricted self-assignment system-role codes, and 13 operational role templates.
- `WmsPlatformBootstrapService` and `WmsTenantProvisioningService` both grant `tenantOwnerTemplate()`.
- Both creation paths invoke `WmsTenantRoleTemplateProvisioner` to create the same 13 immutable, tenant-scoped operational role templates.
- `WmsPlatformBootstrapMySqlIntegrationTest` and `WmsTenantProvisioningMySqlIntegrationTest` compare the persisted role-permission rows with the canonical Java policy, rather than merely comparing constants.
- Flyway V28 reconciles existing tenants' Tenant Owner grants and provisions the operational system roles for tenants already present when the migration runs. Historical migrations V1–V27 remain immutable.

The Tenant Owner baseline contains 30 explicit governance and operational-read permissions. It does not include workflow execution, inventory reservation/posting, shipment release/shipping, stock-count approval, inventory-adjustment approval/posting, or replenishment reversal. Operational capabilities are assigned through the separately provisioned role templates and tenant role assignment workflows.

## 2. Current implementation inventory

### Canonical policy

Source: `src/main/java/com/zirocraft/zirologistics/wms/authorization/domain/WmsTenantPermissionPolicy.java`

The policy distinguishes:
1. the complete permission catalogue seeded by migrations;
2. permissions visible to tenant role managers;
3. permissions eligible for custom-role delegation;
4. the Tenant Owner default grant template;
5. immutable operational system-role templates;
6. system roles restricted from self-assignment.

Catalogue visibility and custom-role delegation are separate policy methods, even though the current sets overlap. Permission replacement is additionally bounded by the caller's own effective permissions. The tenant policy explicitly excludes platform control-plane permissions.

### Owner and operational roles

The Owner template is a 30-code allowlist for identity/tenant governance, role governance, and read-only operational visibility. It is not a wildcard and does not confer operational posting or approval authority.

The 13 provisioned operational system roles are:
- `SYS_TENANT_ADMINISTRATOR`
- `SYS_WAREHOUSE_SUPERVISOR`
- `SYS_INBOUND_OPERATOR`
- `SYS_INBOUND_CONTROLLER`
- `SYS_OUTBOUND_OPERATOR`
- `SYS_OUTBOUND_CONTROLLER`
- `SYS_SHIPPING_CLERK`
- `SYS_INVENTORY_CONTROLLER`
- `SYS_INVENTORY_APPROVER`
- `SYS_TRANSFER_DISPATCHER`
- `SYS_TRANSFER_RECEIVER`
- `SYS_REPLENISHMENT_OPERATOR`
- `SYS_REPORTING_AUDITOR`

These are role templates created for each tenant; they are not mandatory staffing positions. A small warehouse may assign several appropriate roles to one person, subject to the implemented assignment and separation-of-duties checks. Do not infer that every membership automatically receives all templates.

### Creation-path parity

| Creation path | Tenant Owner policy | Operational templates | Evidence |
|---|---|---|---|
| First-install bootstrap | Shared `tenantOwnerTemplate()` | Shared provisioner | `WmsPlatformBootstrapMySqlIntegrationTest` |
| Repeatable tenant provisioning | Shared `tenantOwnerTemplate()` | Shared provisioner | `WmsTenantProvisioningMySqlIntegrationTest` |
| Existing tenants at migration V28 | Additive SQL reconciliation | Additive SQL provisioning | `V28__reconcile_tenant_owner_permission_template.sql` |

The integration tests inspect persisted database rows for the Owner and every system-role template. This is stronger than asserting that two code constants happen to match.

## 3. Delegation and safety boundaries

- Role managers see only the policy-defined catalogue subset.
- Custom-role grants are bounded by the policy-defined delegable set and by the caller's own effective permissions.
- Protected system role codes cannot be created as custom roles.
- System-role permission rows are server-managed and immutable through custom role-management APIs.
- High-risk operational system roles are restricted from self-assignment.
- Tenant Owner does not receive platform permissions or blanket operational execution authority.
- SaaS product entitlements remain independent from tenant RBAC.

## 4. Remaining gaps and assurance limits

The canonical policy resolves the original bootstrap/provisioning mismatch, but it does not complete all authorization governance:

1. **Permission risk metadata:** the immutable `WmsTenantPermissionRiskRegistry` now provides 94 machine-readable entries with risk class, tenant assignability, fresh-MFA, audit, and optional SoD-group metadata. The registry is tested against the seeded catalogue, but it does not itself enforce those controls at every action boundary.
2. **Separation of duties:** stock-count and inventory-adjustment actor separation exists in their workflows, and high-risk system-role self-assignment is restricted. Inbound posting, final shipping, and transfer dispatch/receipt actor separation is not universally certified across all routes and lifecycle paths.
3. **Delegation policy coverage:** catalogue visibility and custom-role delegation are centralized, but the allowed permission subset remains deliberately restricted. Do not broaden it without route-level enforcement review and negative tests.
4. **Permission enforcement completeness:** this audit does not certify that every controller, service method, background worker, and indirect resource lookup enforces the intended permission and tenant scope.
5. **Tenant staffing ergonomics:** templates are available as defaults, but onboarding UX, role assignment guidance, and safe multi-role staffing workflows remain product work.

## 5. Required follow-up

1. Build a canonical permission metadata registry with stable risk class and operation semantics, after auditing every seeded permission and its enforcement point.
2. Trace each sensitive workflow from HTTP route through service and SQL to verify both tenant scope and permission checks.
3. Add negative MySQL tests for cross-actor inbound posting, shipment release/shipping, transfer dispatch/receipt, and any operation where the same actor must not perform both sides.
4. Verify that tenant role assignment cannot bypass self-assignment restrictions, delegation ceilings, protected-role immutability, or last-owner protections.
5. Keep platform permissions, tenant permissions, and subscription entitlements as three distinct authorization dimensions.
6. Do not add a migration merely to restate the existing policy. Use an additive migration only if durable permission metadata or new role-template storage is actually required.

## 6. Verification references

Primary implementation:
- `src/main/java/com/zirocraft/zirologistics/wms/authorization/domain/WmsTenantPermissionPolicy.java`
- `src/main/java/com/zirocraft/zirologistics/wms/authorization/application/WmsTenantRoleTemplateProvisioner.java`
- `src/main/java/com/zirocraft/zirologistics/wms/bootstrap/WmsPlatformBootstrapService.java`
- `src/main/java/com/zirocraft/zirologistics/wms/tenancy/application/WmsTenantProvisioningService.java`
- `src/main/resources/db/wms-migration/V28__reconcile_tenant_owner_permission_template.sql`

Persisted MySQL evidence:
- `src/test/java/com/zirocraft/zirologistics/wms/bootstrap/WmsPlatformBootstrapMySqlIntegrationTest.java`
- `src/test/java/com/zirocraft/zirologistics/wms/tenancy/WmsTenantProvisioningMySqlIntegrationTest.java`

No production database, local developer database, bootstrap operation, or operator credential was used or modified during this audit.
