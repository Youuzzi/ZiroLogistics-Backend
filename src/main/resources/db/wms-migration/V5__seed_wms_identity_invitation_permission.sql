-- Seed the explicit permission required to invite a user into the current tenant.
-- This is a global permission catalog entry only; no tenant role is granted it automatically.

INSERT INTO `permissions` (`permission_code`, `module`, `description`)
VALUES (
  'WMS_IDENTITY_MEMBERSHIP_INVITE',
  'IDENTITY',
  'Allows an active tenant membership with this permission to invite a user into the same tenant.'
);
