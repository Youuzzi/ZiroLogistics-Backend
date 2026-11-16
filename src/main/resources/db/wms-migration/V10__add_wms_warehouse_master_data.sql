-- Add tenant-scoped warehouse and location management permissions and audit events.
-- V1-V9 remain frozen; no new table is required for Stage 7.

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
    'WAREHOUSE_LOCATION_STATUS_CHANGED'
  ));

INSERT INTO permissions (permission_code, module, description) VALUES
  ('WMS_WAREHOUSE_READ', 'WAREHOUSE', 'Read tenant warehouses.'),
  ('WMS_WAREHOUSE_CREATE', 'WAREHOUSE', 'Create tenant warehouses.'),
  ('WMS_WAREHOUSE_UPDATE', 'WAREHOUSE', 'Update tenant warehouse metadata.'),
  ('WMS_WAREHOUSE_STATUS_MANAGE', 'WAREHOUSE', 'Change tenant warehouse lifecycle status.'),
  ('WMS_LOCATION_READ', 'WAREHOUSE', 'Read warehouse locations.'),
  ('WMS_LOCATION_CREATE', 'WAREHOUSE', 'Create warehouse locations.'),
  ('WMS_LOCATION_UPDATE', 'WAREHOUSE', 'Update warehouse location metadata.'),
  ('WMS_LOCATION_STATUS_MANAGE', 'WAREHOUSE', 'Change warehouse location status.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.permission_code IN (
  'WMS_WAREHOUSE_READ', 'WMS_WAREHOUSE_CREATE', 'WMS_WAREHOUSE_UPDATE',
  'WMS_WAREHOUSE_STATUS_MANAGE', 'WMS_LOCATION_READ', 'WMS_LOCATION_CREATE',
  'WMS_LOCATION_UPDATE', 'WMS_LOCATION_STATUS_MANAGE'
)
WHERE r.role_code = 'TENANT_OWNER' AND r.is_system_role = 1;
