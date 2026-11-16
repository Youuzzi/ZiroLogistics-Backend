-- Tenant profile management is an explicit capability, separate from invitation permission.
INSERT INTO permissions (permission_code, module, description)
VALUES (
  'WMS_TENANT_PROFILE_UPDATE',
  'TENANCY',
  'Allows an active tenant membership to update its own tenant organization profile.'
);
