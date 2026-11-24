-- Reconcile existing TENANT_OWNER grants and provision immutable operational system roles.
-- V1-V27 remain immutable. Runtime bootstrap and repeatable provisioning use the same
-- WmsTenantPermissionPolicy templates; this migration applies them to tenants already present.

DELETE rp
FROM role_permissions rp
JOIN roles r ON r.id = rp.role_id
WHERE r.role_code = 'TENANT_OWNER'
  AND r.is_system_role = 1;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.permission_code IN (
  'WMS_IDENTITY_MEMBERSHIP_INVITE',
  'WMS_TENANT_PROFILE_UPDATE',
  'WMS_ROLE_READ',
  'WMS_ROLE_CREATE',
  'WMS_ROLE_UPDATE',
  'WMS_ROLE_DEACTIVATE',
  'WMS_PERMISSION_CATALOG_READ',
  'WMS_ROLE_PERMISSION_MANAGE',
  'WMS_MEMBERSHIP_ROLE_READ',
  'WMS_MEMBERSHIP_ROLE_MANAGE',
  'WMS_WAREHOUSE_READ',
  'WMS_LOCATION_READ',
  'WMS_ITEM_READ',
  'WMS_ITEM_CATEGORY_READ',
  'WMS_UOM_CATALOG_READ',
  'WMS_LOT_READ',
  'WMS_SERIAL_READ',
  'WMS_INVENTORY_READ',
  'WMS_INVENTORY_LEDGER_READ',
  'WMS_INBOUND_RECEIPT_READ',
  'WMS_PUTAWAY_READ',
  'WMS_OUTBOUND_SHIPMENT_READ',
  'WMS_PICKING_READ',
  'WMS_PACKING_READ',
  'WMS_SHIPPING_READ',
  'WMS_TRANSFER_READ',
  'WMS_STOCK_COUNT_READ',
  'WMS_INVENTORY_ADJUSTMENT_READ',
  'WMS_REPLENISHMENT_READ',
  'WMS_OPERATIONAL_REPORT_READ'
)
WHERE r.role_code = 'TENANT_OWNER'
  AND r.is_system_role = 1;

-- Reserved role-code conflicts fail the migration instead of silently omitting a template.
INSERT INTO roles (public_id, tenant_id, role_code, role_name, description, is_system_role, status)
SELECT UUID(), t.id, templates.role_code, templates.role_name, templates.description, 1, 'ACTIVE'
FROM tenants t
CROSS JOIN (
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'Tenant Administrator' AS role_name, 'Delegated tenant profile and master-data administration without role governance or inventory posting.' AS description
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'Warehouse Supervisor' AS role_name, 'Coordinates warehouse tasks and assignments without inventory posting or approval authority.' AS description
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'Inbound Operator' AS role_name, 'Creates and processes inbound receipts and putaway work without receipt posting authority.' AS description
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'Inbound Controller' AS role_name, 'Independently posts completed inbound receipts and reviews resulting inventory evidence.' AS description
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'Outbound Operator' AS role_name, 'Prepares shipments, reserves inventory, and executes picking and packing without final shipping commit.' AS description
  UNION ALL
  SELECT 'SYS_OUTBOUND_CONTROLLER' AS role_code, 'Outbound Controller' AS role_name, 'Independently releases allocated outbound shipments before picking and final shipping.' AS description
  UNION ALL
  SELECT 'SYS_SHIPPING_CLERK' AS role_code, 'Shipping Clerk' AS role_name, 'Performs final shipping handoff after outbound preparation and packing are complete.' AS description
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'Inventory Controller' AS role_name, 'Prepares stock counts and inventory adjustments and executes routine replenishment work.' AS description
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'Inventory Approver' AS role_name, 'Independently reviews and approves stock-count variances and inventory adjustments.' AS description
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'Transfer Dispatcher' AS role_name, 'Creates, releases, and dispatches inter-warehouse transfers from the source location.' AS description
  UNION ALL
  SELECT 'SYS_TRANSFER_RECEIVER' AS role_code, 'Transfer Receiver' AS role_name, 'Receives dispatched inter-warehouse transfers at the destination location.' AS description
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'Replenishment Operator' AS role_name, 'Executes replenishment tasks without permission to reverse completed confirmations.' AS description
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'Reporting Auditor' AS role_name, 'Read-only access to tenant operational reports and workflow evidence.' AS description
) templates;

-- The generic inventory-post capability is paired with workflow-specific permissions on
-- movement-performing roles. Approval and execution remain assigned to separate templates.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN (
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_IDENTITY_MEMBERSHIP_INVITE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_TENANT_PROFILE_UPDATE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_WAREHOUSE_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_WAREHOUSE_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_WAREHOUSE_UPDATE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_WAREHOUSE_STATUS_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_LOCATION_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_LOCATION_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_LOCATION_UPDATE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_LOCATION_STATUS_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_ITEM_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_ITEM_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_ITEM_UPDATE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_ITEM_STATUS_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_ITEM_CATEGORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_ITEM_CATEGORY_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_ITEM_IDENTIFIER_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_ITEM_UOM_CONVERSION_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_UOM_CATALOG_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_LOT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_LOT_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_SERIAL_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_SERIAL_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_INVENTORY_LEDGER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TENANT_ADMINISTRATOR' AS role_code, 'WMS_OPERATIONAL_REPORT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_WAREHOUSE_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_LOCATION_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_ITEM_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_INVENTORY_LEDGER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_INBOUND_RECEIPT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_PUTAWAY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_PUTAWAY_ASSIGN' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_OUTBOUND_SHIPMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_PICKING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_PICKING_ASSIGN' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_PACKING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_PACKING_ASSIGN' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_SHIPPING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_TRANSFER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_STOCK_COUNT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_INVENTORY_ADJUSTMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_REPLENISHMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_REPLENISHMENT_TASK_RELEASE' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_REPLENISHMENT_TASK_ASSIGN' AS permission_code
  UNION ALL
  SELECT 'SYS_WAREHOUSE_SUPERVISOR' AS role_code, 'WMS_OPERATIONAL_REPORT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_INBOUND_RECEIPT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_INBOUND_RECEIPT_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_INBOUND_RECEIPT_UPDATE' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_PUTAWAY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_PUTAWAY_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_PUTAWAY_START' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_PUTAWAY_CONFIRM' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_WAREHOUSE_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_LOCATION_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_ITEM_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_UOM_CATALOG_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_LOT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_SERIAL_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_OPERATOR' AS role_code, 'WMS_INVENTORY_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'WMS_INBOUND_RECEIPT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'WMS_INBOUND_RECEIPT_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'WMS_INVENTORY_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'WMS_INVENTORY_LEDGER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'WMS_WAREHOUSE_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'WMS_LOCATION_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'WMS_ITEM_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'WMS_LOT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INBOUND_CONTROLLER' AS role_code, 'WMS_SERIAL_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_OUTBOUND_SHIPMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_OUTBOUND_SHIPMENT_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_OUTBOUND_SHIPMENT_UPDATE' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_OUTBOUND_SHIPMENT_ALLOCATE' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_INVENTORY_RESERVE' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_INVENTORY_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PICKING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PICKING_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PICKING_ASSIGN' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PICKING_START' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PICKING_CONFIRM' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PACKING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PACKING_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PACKING_READY' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PACKING_ASSIGN' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PACKING_START' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PACKING_PACKAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PACKING_SEAL' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_PACKING_COMPLETE' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_WAREHOUSE_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_LOCATION_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_ITEM_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_LOT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_OPERATOR' AS role_code, 'WMS_SERIAL_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_CONTROLLER' AS role_code, 'WMS_OUTBOUND_SHIPMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_CONTROLLER' AS role_code, 'WMS_OUTBOUND_SHIPMENT_RELEASE' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_CONTROLLER' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_CONTROLLER' AS role_code, 'WMS_INVENTORY_LEDGER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_OUTBOUND_CONTROLLER' AS role_code, 'WMS_PACKING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_SHIPPING_CLERK' AS role_code, 'WMS_SHIPPING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_SHIPPING_CLERK' AS role_code, 'WMS_SHIPPING_SHIP' AS permission_code
  UNION ALL
  SELECT 'SYS_SHIPPING_CLERK' AS role_code, 'WMS_INVENTORY_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_SHIPPING_CLERK' AS role_code, 'WMS_OUTBOUND_SHIPMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_SHIPPING_CLERK' AS role_code, 'WMS_PACKING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_SHIPPING_CLERK' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_INVENTORY_LEDGER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_INVENTORY_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_LOT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_LOT_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_SERIAL_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_SERIAL_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_STOCK_COUNT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_STOCK_COUNT_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_STOCK_COUNT_PLAN' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_STOCK_COUNT_START' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_STOCK_COUNT_RECORD' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_STOCK_COUNT_SUBMIT' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_STOCK_COUNT_CANCEL' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_INVENTORY_ADJUSTMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_INVENTORY_ADJUSTMENT_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_INVENTORY_ADJUSTMENT_SUBMIT' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_REPLENISHMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_REPLENISHMENT_RULE_MANAGE' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_REPLENISHMENT_TASK_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_REPLENISHMENT_TASK_RELEASE' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_REPLENISHMENT_TASK_ASSIGN' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_REPLENISHMENT_TASK_START' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_REPLENISHMENT_TASK_CONFIRM' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_REPLENISHMENT_TASK_CANCEL' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_WAREHOUSE_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_LOCATION_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_CONTROLLER' AS role_code, 'WMS_ITEM_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_STOCK_COUNT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_STOCK_COUNT_REVIEW' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_STOCK_COUNT_APPROVE' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_STOCK_COUNT_CANCEL' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_INVENTORY_ADJUSTMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_INVENTORY_ADJUSTMENT_APPROVE' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_INVENTORY_ADJUSTMENT_REJECT' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_INVENTORY_ADJUSTMENT_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_INVENTORY_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_INVENTORY_APPROVER' AS role_code, 'WMS_INVENTORY_LEDGER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_TRANSFER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_TRANSFER_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_TRANSFER_RELEASE' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_TRANSFER_DISPATCH' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_INVENTORY_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_WAREHOUSE_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_LOCATION_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_ITEM_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_LOT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_DISPATCHER' AS role_code, 'WMS_SERIAL_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_RECEIVER' AS role_code, 'WMS_TRANSFER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_RECEIVER' AS role_code, 'WMS_TRANSFER_RECEIVE' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_RECEIVER' AS role_code, 'WMS_INVENTORY_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_RECEIVER' AS role_code, 'WMS_WAREHOUSE_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_RECEIVER' AS role_code, 'WMS_LOCATION_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_RECEIVER' AS role_code, 'WMS_ITEM_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_RECEIVER' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_RECEIVER' AS role_code, 'WMS_LOT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_TRANSFER_RECEIVER' AS role_code, 'WMS_SERIAL_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_REPLENISHMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_REPLENISHMENT_TASK_CREATE' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_REPLENISHMENT_TASK_RELEASE' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_REPLENISHMENT_TASK_ASSIGN' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_REPLENISHMENT_TASK_START' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_REPLENISHMENT_TASK_CONFIRM' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_REPLENISHMENT_TASK_CANCEL' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_INVENTORY_POST' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_WAREHOUSE_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_LOCATION_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_ITEM_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_LOT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPLENISHMENT_OPERATOR' AS role_code, 'WMS_SERIAL_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_OPERATIONAL_REPORT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_WAREHOUSE_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_LOCATION_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_ITEM_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_LOT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_SERIAL_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_INVENTORY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_INVENTORY_LEDGER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_INBOUND_RECEIPT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_PUTAWAY_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_OUTBOUND_SHIPMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_PICKING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_PACKING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_SHIPPING_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_TRANSFER_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_STOCK_COUNT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_INVENTORY_ADJUSTMENT_READ' AS permission_code
  UNION ALL
  SELECT 'SYS_REPORTING_AUDITOR' AS role_code, 'WMS_REPLENISHMENT_READ' AS permission_code
) template_grants ON template_grants.role_code = r.role_code
JOIN permissions p ON p.permission_code = template_grants.permission_code
WHERE r.is_system_role = 1;
