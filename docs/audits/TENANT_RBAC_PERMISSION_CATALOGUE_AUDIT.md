# Tenant RBAC Permission Catalogue and Provisioning Audit

**Status:** Source and migration audit baseline; policy reconciliation and implementation remain open.
**Reviewed commit:** `bcd21a8efee2bf44d529c3cec8334b39e8477c28`
**Scope:** WMS permission seeds in Flyway V5 and V7–V26, first-install bootstrap grants, repeatable tenant-provisioning grants, role-management catalogue/delegation policy.
**Assurance:** This document inventories declared permission codes and grant-path differences. It does not certify every permission enforcement point or approve a final Tenant Owner role.

## 1. Executive findings

The repository currently has three different permission policies for newly created tenants:

1. **First-install bootstrap:** `WmsPlatformBootstrapService.OWNER_PERMISSIONS` grants only `WMS_IDENTITY_MEMBERSHIP_INVITE` and `WMS_TENANT_PROFILE_UPDATE`.
2. **Repeatable provisioning:** `WmsTenantProvisioningService.OWNER_PERMISSIONS` grants a wider, explicit list of identity, tenant, role administration, warehouse/master-data, inventory read/post, and reporting permissions. It omits most workflow-specific permissions introduced in V13–V25.
3. **Role delegation:** `WmsRoleManagementService.DELEGABLE_PERMISSIONS` and its SQL permission-catalogue query expose only a small subset of the global permission catalogue. Several permissions granted to the provisioning owner are intentionally not delegable, including role-permission management and membership-role management.

Flyway V9–V26 insert global permission catalogue entries and backfill owner grants to `TENANT_OWNER` roles that exist when each migration executes. This historical migration backfill does not grant permissions to tenant roles created later by repeatable provisioning. Therefore, newly provisioned tenants can receive an owner role with no permission grants for substantial inbound/outbound/warehouse execution workflows.

The mismatch is a functional authorization gap, not a reason to grant every permission to every owner. A reviewed canonical policy must distinguish owner governance, operational execution, sensitive posting/approval, and read-only capabilities.

## 2. Evidence reviewed

### Application source
- `WmsPlatformBootstrapService.java` — bootstrap owner allowlist.
- `WmsTenantProvisioningService.java` — repeatable provisioning owner allowlist and tenant/role creation.
- `WmsRoleManagementService.java` — role permission catalogue, delegable allowlist, caller-authority check, and membership-role management.

### Flyway
- V5 — identity membership invitation permission.
- V7 — tenant profile update permission.
- V9–V20 — role governance, warehouse/master data, inventory, inbound, putaway, outbound, picking, packing, shipping, transfer, and stock-count permissions.
- V21–V22 — stock-count serial evidence and review metadata; no new permission codes.
- V23–V25 — inventory adjustment and replenishment permissions.
- V26 — operational reporting permission.
- V27 — user credential version; no tenant WMS permission catalogue addition.

The V1 schema and all merged migrations remain immutable. Any policy correction must use application changes and, where durable catalogue changes are necessary, a new additive migration.

## 3. Permission catalogue declared by migrations

The following codes were found in the reviewed permission-seeding migrations.

### Identity and tenant governance
- `WMS_IDENTITY_MEMBERSHIP_INVITE`
- `WMS_TENANT_PROFILE_UPDATE`

### Role and membership governance (V9)
- `WMS_ROLE_READ`
- `WMS_ROLE_CREATE`
- `WMS_ROLE_UPDATE`
- `WMS_ROLE_DEACTIVATE`
- `WMS_PERMISSION_CATALOG_READ`
- `WMS_ROLE_PERMISSION_MANAGE`
- `WMS_MEMBERSHIP_ROLE_READ`
- `WMS_MEMBERSHIP_ROLE_MANAGE`

### Warehouse and location (V10)
- `WMS_WAREHOUSE_READ`
- `WMS_WAREHOUSE_CREATE`
- `WMS_WAREHOUSE_UPDATE`
- `WMS_WAREHOUSE_STATUS_MANAGE`
- `WMS_LOCATION_READ`
- `WMS_LOCATION_CREATE`
- `WMS_LOCATION_UPDATE`
- `WMS_LOCATION_STATUS_MANAGE`

### Item and inventory master data (V11–V12)
- `WMS_ITEM_READ`
- `WMS_ITEM_CREATE`
- `WMS_ITEM_UPDATE`
- `WMS_ITEM_STATUS_MANAGE`
- `WMS_ITEM_CATEGORY_READ`
- `WMS_ITEM_CATEGORY_MANAGE`
- `WMS_ITEM_IDENTIFIER_MANAGE`
- `WMS_ITEM_UOM_CONVERSION_MANAGE`
- `WMS_UOM_CATALOG_READ`
- `WMS_LOT_READ`
- `WMS_LOT_MANAGE`
- `WMS_SERIAL_READ`
- `WMS_SERIAL_MANAGE`
- `WMS_INVENTORY_READ`
- `WMS_INVENTORY_LEDGER_READ`
- `WMS_INVENTORY_POST`

### Inbound receiving (V13)
- `WMS_INBOUND_RECEIPT_READ`
- `WMS_INBOUND_RECEIPT_CREATE`
- `WMS_INBOUND_RECEIPT_UPDATE`
- `WMS_INBOUND_RECEIPT_POST`

### Putaway (V14)
- `WMS_PUTAWAY_READ`
- `WMS_PUTAWAY_CREATE`
- `WMS_PUTAWAY_ASSIGN`
- `WMS_PUTAWAY_START`
- `WMS_PUTAWAY_CONFIRM`

### Outbound and inventory reservation (V15)
- `WMS_INVENTORY_RESERVE`
- `WMS_OUTBOUND_SHIPMENT_READ`
- `WMS_OUTBOUND_SHIPMENT_CREATE`
- `WMS_OUTBOUND_SHIPMENT_UPDATE`
- `WMS_OUTBOUND_SHIPMENT_ALLOCATE`
- `WMS_OUTBOUND_SHIPMENT_RELEASE`

### Picking (V16)
- `WMS_PICKING_READ`
- `WMS_PICKING_CREATE`
- `WMS_PICKING_ASSIGN`
- `WMS_PICKING_START`
- `WMS_PICKING_CONFIRM`

### Packing (V17)
- `WMS_PACKING_READ`
- `WMS_PACKING_CREATE`
- `WMS_PACKING_READY`
- `WMS_PACKING_ASSIGN`
- `WMS_PACKING_START`
- `WMS_PACKING_PACKAGE`
- `WMS_PACKING_SEAL`
- `WMS_PACKING_COMPLETE`

### Shipping (V18)
- `WMS_SHIPPING_READ`
- `WMS_SHIPPING_SHIP`

### Inter-warehouse transfer (V19)
- `WMS_TRANSFER_READ`
- `WMS_TRANSFER_CREATE`
- `WMS_TRANSFER_RELEASE`
- `WMS_TRANSFER_DISPATCH`
- `WMS_TRANSFER_RECEIVE`

### Stock count (V20)
- `WMS_STOCK_COUNT_READ`
- `WMS_STOCK_COUNT_CREATE`
- `WMS_STOCK_COUNT_PLAN`
- `WMS_STOCK_COUNT_START`
- `WMS_STOCK_COUNT_RECORD`
- `WMS_STOCK_COUNT_SUBMIT`
- `WMS_STOCK_COUNT_REVIEW`
- `WMS_STOCK_COUNT_APPROVE`
- `WMS_STOCK_COUNT_CANCEL`

### Inventory adjustment (V23)
- `WMS_INVENTORY_ADJUSTMENT_READ`
- `WMS_INVENTORY_ADJUSTMENT_CREATE`
- `WMS_INVENTORY_ADJUSTMENT_SUBMIT`
- `WMS_INVENTORY_ADJUSTMENT_APPROVE`
- `WMS_INVENTORY_ADJUSTMENT_REJECT`
- `WMS_INVENTORY_ADJUSTMENT_POST`

### Replenishment (V24–V25)
- `WMS_REPLENISHMENT_READ`
- `WMS_REPLENISHMENT_RULE_MANAGE`
- `WMS_REPLENISHMENT_TASK_CREATE`
- `WMS_REPLENISHMENT_TASK_RELEASE`
- `WMS_REPLENISHMENT_TASK_ASSIGN`
- `WMS_REPLENISHMENT_TASK_START`
- `WMS_REPLENISHMENT_TASK_CONFIRM`
- `WMS_REPLENISHMENT_TASK_CANCEL`
- `WMS_REPLENISHMENT_CONFIRMATION_REVERSE`

### Reporting (V26)
- `WMS_OPERATIONAL_REPORT_READ`

V21 and V22 add relational evidence for stock-count serial observations and reviewer attribution; they do not add permission codes. V27 is a credential-version migration, not an authorization catalogue migration.

## 4. Grant-path comparison

### First-install bootstrap

The bootstrap service currently grants exactly:
- `WMS_IDENTITY_MEMBERSHIP_INVITE`
- `WMS_TENANT_PROFILE_UPDATE`

It creates a `TENANT_OWNER` system role but does not use the wider provisioning allowlist. This is too narrow to manage roles or execute WMS workflows, but must not be silently widened without the reviewed owner policy.

### Repeatable tenant provisioning

The provisioning service currently grants:
- Identity and tenant: invitation and tenant profile update.
- Role governance: role read/create/update/deactivate, permission catalogue read, role permission manage, membership-role read/manage.
- Warehouse/location: read/create/update/status-manage for both.
- Item/inventory master data: item read/create/update/status-manage; category read/manage; item identifier and UOM conversion manage; UOM catalogue read; lot and serial read/manage.
- Inventory: inventory read, ledger read, inventory post.
- Reporting: operational report read.

It does **not** currently grant the V13–V25 workflow permissions for inbound, putaway, outbound, picking, packing, shipping, transfer, stock count, inventory adjustment, or replenishment. It also does not grant `WMS_INVENTORY_RESERVE`.

### Migration backfills

Migrations V9–V20 and V23–V26 backfill permission grants to matching `TENANT_OWNER` system roles present at migration execution. This is appropriate for upgrading an existing installation but is not a durable default for tenants provisioned after the migration history is already complete. Repeatable provisioning must explicitly apply the approved baseline for every new tenant.

### Role catalogue and delegation

The role-management endpoint currently exposes only this restricted subset:
- `WMS_ROLE_READ`, `WMS_ROLE_CREATE`, `WMS_ROLE_UPDATE`, `WMS_ROLE_DEACTIVATE`
- `WMS_PERMISSION_CATALOG_READ`, `WMS_MEMBERSHIP_ROLE_READ`
- `WMS_WAREHOUSE_READ`, `WMS_WAREHOUSE_CREATE`, `WMS_WAREHOUSE_UPDATE`
- `WMS_LOCATION_READ`, `WMS_LOCATION_CREATE`, `WMS_LOCATION_UPDATE`
- `WMS_ITEM_READ`, `WMS_ITEM_CREATE`, `WMS_ITEM_UPDATE`
- `WMS_ITEM_CATEGORY_READ`, `WMS_ITEM_CATEGORY_MANAGE`
- `WMS_UOM_CATALOG_READ`, `WMS_INVENTORY_READ`, `WMS_INVENTORY_LEDGER_READ`

Permission replacement also requires every requested code to be present in the caller's own effective permission set. This is a useful anti-escalation constraint. The separate delegable allowlist further prevents delegation of sensitive governance permissions such as `WMS_ROLE_PERMISSION_MANAGE` and `WMS_MEMBERSHIP_ROLE_MANAGE`, and excludes many operational permissions. The current catalogue response and allowlist are hard-coded separately and can drift.

## 5. Security and product-policy implications

1. A tenant owner with no workflow permissions may be unable to operate a newly provisioned tenant despite the modules existing in the backend.
2. Existing tenants whose owner roles were present during migration execution can have materially different grants from tenants created later.
3. A complete permission catalogue response is not the same as permission delegation authority. The API should distinguish discoverable permissions from grantable permissions.
4. The caller's own authority must remain an upper bound. A caller must never grant a permission they do not themselves hold, unless a separately approved, explicit owner-governance rule says otherwise.
5. Sensitive posting, stock adjustment approval, stock count approval, serial/lot management, transfer dispatch/receipt, shipment release/shipping, and replenishment reversal should be evaluated for separation of duties. A blanket `TENANT_OWNER = all permissions` grant would undermine these controls.
6. Tenant plan entitlements remain separate from RBAC. A user permission must not imply the tenant subscription includes that module.

## 6. Recommended canonical policy structure

The following is a design recommendation, not an assertion that these roles or grants are already implemented:

- **Tenant Owner:** tenant governance, membership invitation, role and delegation governance, profile administration, and explicitly approved oversight/read capabilities. Do not automatically combine every operational execution and approval permission.
- **Tenant Administrator:** delegated tenant administration without platform privileges or automatic authority to approve/post high-risk inventory changes.
- **Warehouse Supervisor:** operational visibility, task assignment, release/confirm authority according to workflow, with approval boundaries defined per module.
- **Inbound Operator:** receipt draft/update and receiving execution, with posting rights separately reviewed.
- **Outbound Operator:** shipment preparation, allocation, picking/packing/shipping rights separated by operational responsibility.
- **Inventory Controller:** stock visibility, stock count planning/review, lot/serial controls, and adjustment submission/review according to separation-of-duties rules.
- **Inventory Approver:** independent approval/posting permissions for stock count variance and inventory adjustment; cannot approve their own submission.
- **Transfer Operator:** transfer creation/release/dispatch/receive split by source and destination responsibilities where required.
- **Replenishment Operator:** rule/task execution with confirmation and reversal rights separately reviewed.
- **Reporting/Auditor:** read-only operational reports and permitted audit evidence; no mutation permissions.

These are role templates for policy design. Do not seed them until the full permission-to-role matrix, self-approval rules, delegation ceilings, and least-privilege defaults are reviewed and tested.

## 7. Required reconciliation and implementation sequence

1. Treat this migration inventory as the current declared permission catalogue; verify the final catalogue against a fresh MySQL 8.4 migration run and persisted `permissions` table.
2. Produce one canonical server-side permission policy source used by bootstrap, repeatable provisioning, role catalogue responses, and delegation validation.
3. Separate:
   - globally defined permission codes;
   - permissions visible to a caller;
   - permissions grantable by a caller;
   - permissions assigned by each system role template.
4. Define a complete Tenant Owner baseline and operational role templates with risk tiers and separation-of-duties constraints.
5. Ensure a newly provisioned tenant receives the same reviewed baseline as an installation bootstrapped on a fresh database.
6. Do not grant all permissions to the owner merely to close the current mismatch.
7. Add MySQL/Testcontainers tests that compare the seeded catalogue with the canonical policy, verify bootstrap/provisioning parity, and verify role delegation for every permission class.
8. Add negative tests for privilege escalation, self-approval, last-owner removal, cross-tenant role/membership references, and audit rollback.
9. Only after the policy is approved, implement the shared policy and update source/docs. Use a new additive Flyway migration only if catalogue metadata or durable role-template storage requires it; never edit V1–V27.
10. Re-run all authorization and tenant-isolation suites on integrated `main`.

## 8. Current status

| Area | Status |
|---|---|
| Migration permission inventory | Audited from V5 and V7–V26 source |
| Bootstrap owner grants | Verified: 2 permissions |
| Repeatable provisioning owner grants | Verified: explicit 35-code list |
| Existing-tenant migration backfills | Verified in migration source |
| Role permission catalogue | Verified: restricted hard-coded subset |
| Delegation ceiling | Verified: restricted hard-coded subset plus caller-authority check |
| Canonical shared policy source | Not implemented |
| Complete role-to-permission matrix | Not approved or implemented |
| New-tenant bootstrap/provisioning parity | Not achieved |
| Separation-of-duties enforcement across all workflows | Not certified |

No database, tenant, credential, Docker, or bootstrap operation was performed during this audit. The legacy `zirologistics_db` and frozen reference database `zirowms_v1` remain untouched.
