-- Stage 12 WP7: tenant-scoped, read-only WMS operational reporting permission.
-- V1-V25 remain immutable; this additive migration introduces no new business tables.

INSERT INTO permissions (permission_code, module, description)
VALUES (
  'WMS_OPERATIONAL_REPORT_READ',
  'REPORTING',
  'Read tenant-scoped WMS operational inventory and workflow summaries.'
);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.permission_code = 'WMS_OPERATIONAL_REPORT_READ'
WHERE r.role_code = 'TENANT_OWNER' AND r.is_system_role = 1;
