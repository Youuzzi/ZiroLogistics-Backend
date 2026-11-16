-- Stage 8 inventory posting authorization and audit event.
-- V1-V11 remain immutable. Posting logic is introduced in a later application change.

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
    'INVENTORY_SERIAL_REGISTERED', 'INVENTORY_SERIAL_STATUS_CHANGED',
    'INVENTORY_MOVEMENT_POSTED'
  ));

INSERT INTO permissions (permission_code, module, description)
VALUES ('WMS_INVENTORY_POST', 'INVENTORY',
        'Post validated inventory movements with atomic balance and ledger updates.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.permission_code = 'WMS_INVENTORY_POST'
WHERE r.role_code = 'TENANT_OWNER' AND r.is_system_role = 1;
