-- Tenant profile management is an explicit capability, separate from invitation permission.
INSERT INTO permissions (permission_code, module, description)
VALUES (
  'WMS_TENANT_PROFILE_UPDATE',
  'TENANCY',
  'Allows an active tenant membership to update its own tenant organization profile.'
);

-- Existing system TENANT_OWNER roles receive only this narrowly scoped profile capability.
-- Custom roles and all other tenant roles are deliberately untouched.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.permission_code = 'WMS_TENANT_PROFILE_UPDATE'
WHERE r.role_code = 'TENANT_OWNER'
  AND r.is_system_role = 1
  AND r.status = 'ACTIVE'
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions existing
    WHERE existing.role_id = r.id AND existing.permission_id = p.id
  );
