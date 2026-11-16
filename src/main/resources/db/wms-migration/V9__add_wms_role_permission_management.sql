-- Add tenant-scoped role administration permissions and audit event types.
-- V1-V8 remain frozen. Existing tenant owners receive the explicit management allowlist.

ALTER TABLE tenant_audit_events
  DROP CHECK chk_tenant_audit_events_type;

ALTER TABLE tenant_audit_events
  ADD CONSTRAINT chk_tenant_audit_events_type
  CHECK (event_type IN (
    'TENANT_PROFILE_UPDATED', 'TENANT_SUSPENDED', 'TENANT_REACTIVATED',
    'TENANT_CLOSED', 'TENANT_PROVISIONED',
    'ROLE_CREATED', 'ROLE_UPDATED', 'ROLE_DEACTIVATED',
    'ROLE_PERMISSIONS_REPLACED', 'MEMBERSHIP_ROLE_ASSIGNED',
    'MEMBERSHIP_ROLE_REMOVED'
  ));

INSERT INTO permissions (permission_code, module, description) VALUES
  ('WMS_ROLE_READ', 'AUTHORIZATION', 'Read tenant-scoped roles.'),
  ('WMS_ROLE_CREATE', 'AUTHORIZATION', 'Create tenant custom roles.'),
  ('WMS_ROLE_UPDATE', 'AUTHORIZATION', 'Update tenant custom role metadata.'),
  ('WMS_ROLE_DEACTIVATE', 'AUTHORIZATION', 'Deactivate tenant custom roles.'),
  ('WMS_PERMISSION_CATALOG_READ', 'AUTHORIZATION', 'Read the server-defined permission catalogue.'),
  ('WMS_ROLE_PERMISSION_MANAGE', 'AUTHORIZATION', 'Replace permissions assigned to a tenant custom role.'),
  ('WMS_MEMBERSHIP_ROLE_READ', 'AUTHORIZATION', 'Read membership role assignments.'),
  ('WMS_MEMBERSHIP_ROLE_MANAGE', 'AUTHORIZATION', 'Assign or remove tenant roles from memberships.');

-- Explicit owner allowlist for existing tenants. Sensitive delegation permissions are
-- granted to the owner only; application policy prevents their delegation to custom roles.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.permission_code IN (
  'WMS_ROLE_READ', 'WMS_ROLE_CREATE', 'WMS_ROLE_UPDATE', 'WMS_ROLE_DEACTIVATE',
  'WMS_PERMISSION_CATALOG_READ', 'WMS_ROLE_PERMISSION_MANAGE',
  'WMS_MEMBERSHIP_ROLE_READ', 'WMS_MEMBERSHIP_ROLE_MANAGE'
)
WHERE r.role_code = 'TENANT_OWNER' AND r.is_system_role = 1
;
