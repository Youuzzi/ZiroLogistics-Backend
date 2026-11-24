-- Reconcile every existing system TENANT_OWNER role to the canonical least-privilege template.
-- Historical V5-V27 migrations remain immutable. New tenants use the same template in
-- bootstrap and repeatable provisioning via WmsTenantPermissionPolicy.

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
