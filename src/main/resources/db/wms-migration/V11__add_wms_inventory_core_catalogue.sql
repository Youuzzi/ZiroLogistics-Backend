-- Stage 8 inventory core catalogue and authorization.
-- V1-V10 are immutable. Inventory tables already exist in V1; this migration
-- only adds the Stage 8 permission catalogue, owner grants, audit event types,
-- and the initial global UOM reference catalogue.

ALTER TABLE tenant_audit_events
  DROP CHECK chk_tenant_audit_events_type;

ALTER TABLE tenant_audit_events
  ADD CONSTRAINT chk_tenant_audit_events_type
  CHECK (event_type IN (
    'TENANT_PROFILE_UPDATED', 'TENANT_SUSPENDED', 'TENANT_REACTIVATED',
    'TENANT_CLOSED', 'TENANT_PROVISIONED',
    'ROLE_CREATED', 'ROLE_UPDATED', 'ROLE_DEACTIVATED',
    'ROLE_PERMISSIONS_REPLACED', 'MEMBERSHIP_ROLE_ASSIGNED',
    'MEMBERSHIP_ROLE_REMOVED',
    'WAREHOUSE_CREATED', 'WAREHOUSE_UPDATED', 'WAREHOUSE_STATUS_CHANGED',
    'WAREHOUSE_LOCATION_CREATED', 'WAREHOUSE_LOCATION_UPDATED',
    'WAREHOUSE_LOCATION_STATUS_CHANGED',
    'ITEM_CATEGORY_CREATED', 'ITEM_CATEGORY_UPDATED', 'ITEM_CATEGORY_STATUS_CHANGED',
    'ITEM_CREATED', 'ITEM_UPDATED', 'ITEM_STATUS_CHANGED',
    'ITEM_IDENTIFIER_ADDED', 'ITEM_IDENTIFIER_UPDATED', 'ITEM_IDENTIFIER_REMOVED',
    'ITEM_UOM_CONVERSION_CREATED', 'ITEM_UOM_CONVERSION_DEACTIVATED',
    'INVENTORY_LOT_CREATED', 'INVENTORY_LOT_STATUS_CHANGED',
    'INVENTORY_SERIAL_REGISTERED', 'INVENTORY_SERIAL_STATUS_CHANGED'
  ));

INSERT INTO permissions (permission_code, module, description) VALUES
  ('WMS_ITEM_READ', 'INVENTORY', 'Read tenant item master data.'),
  ('WMS_ITEM_CREATE', 'INVENTORY', 'Create tenant items.'),
  ('WMS_ITEM_UPDATE', 'INVENTORY', 'Update tenant item metadata.'),
  ('WMS_ITEM_STATUS_MANAGE', 'INVENTORY', 'Change tenant item lifecycle status.'),
  ('WMS_ITEM_CATEGORY_READ', 'INVENTORY', 'Read tenant item categories.'),
  ('WMS_ITEM_CATEGORY_MANAGE', 'INVENTORY', 'Manage tenant item categories.'),
  ('WMS_ITEM_IDENTIFIER_MANAGE', 'INVENTORY', 'Manage tenant item identifiers.'),
  ('WMS_ITEM_UOM_CONVERSION_MANAGE', 'INVENTORY', 'Manage item-specific UOM conversions.'),
  ('WMS_UOM_CATALOG_READ', 'INVENTORY', 'Read the global unit-of-measure catalogue.'),
  ('WMS_LOT_READ', 'INVENTORY', 'Read tenant inventory lots.'),
  ('WMS_LOT_MANAGE', 'INVENTORY', 'Manage tenant inventory lots.'),
  ('WMS_SERIAL_READ', 'INVENTORY', 'Read tenant inventory serials.'),
  ('WMS_SERIAL_MANAGE', 'INVENTORY', 'Register and manage tenant inventory serials.'),
  ('WMS_INVENTORY_READ', 'INVENTORY', 'Read tenant inventory balances.'),
  ('WMS_INVENTORY_LEDGER_READ', 'INVENTORY', 'Read tenant immutable inventory ledger entries.');

-- Existing system owner roles receive the explicit Stage 8 allowlist.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.permission_code IN (
  'WMS_ITEM_READ', 'WMS_ITEM_CREATE', 'WMS_ITEM_UPDATE', 'WMS_ITEM_STATUS_MANAGE',
  'WMS_ITEM_CATEGORY_READ', 'WMS_ITEM_CATEGORY_MANAGE',
  'WMS_ITEM_IDENTIFIER_MANAGE', 'WMS_ITEM_UOM_CONVERSION_MANAGE',
  'WMS_UOM_CATALOG_READ', 'WMS_LOT_READ', 'WMS_LOT_MANAGE',
  'WMS_SERIAL_READ', 'WMS_SERIAL_MANAGE',
  'WMS_INVENTORY_READ', 'WMS_INVENTORY_LEDGER_READ'
)
WHERE r.role_code = 'TENANT_OWNER' AND r.is_system_role = 1;

-- Stable global catalogue. decimal_places is the maximum accepted display/input
-- precision for a unit; inventory posting still requires exact DECIMAL(19,4).
INSERT INTO units_of_measure (uom_code, uom_name, dimension, decimal_places, is_active) VALUES
  ('EA', 'Each', 'COUNT', 0, 1),
  ('PACK', 'Pack', 'COUNT', 0, 1),
  ('CASE', 'Case', 'COUNT', 0, 1),
  ('PALLET', 'Pallet', 'COUNT', 0, 1),
  ('KG', 'Kilogram', 'MASS', 3, 1),
  ('G', 'Gram', 'MASS', 0, 1),
  ('MG', 'Milligram', 'MASS', 0, 1),
  ('TONNE', 'Metric tonne', 'MASS', 4, 1),
  ('M', 'Metre', 'LENGTH', 3, 1),
  ('CM', 'Centimetre', 'LENGTH', 1, 1),
  ('MM', 'Millimetre', 'LENGTH', 0, 1),
  ('M2', 'Square metre', 'AREA', 2, 1),
  ('CM2', 'Square centimetre', 'AREA', 0, 1),
  ('L', 'Litre', 'VOLUME', 3, 1),
  ('ML', 'Millilitre', 'VOLUME', 0, 1),
  ('M3', 'Cubic metre', 'VOLUME', 3, 1),
  ('HOUR', 'Hour', 'TIME', 2, 1),
  ('MIN', 'Minute', 'TIME', 0, 1);
