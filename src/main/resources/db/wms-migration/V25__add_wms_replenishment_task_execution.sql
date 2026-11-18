-- Replenishment task execution permissions and immutable serial-to-confirmation evidence.
-- The V1 confirmation table supports one-time reversal only after this unique key is added.
ALTER TABLE replenishment_confirmations
  ADD UNIQUE KEY uk_replenishment_confirmations_single_reversal (tenant_id, reverses_confirmation_id);

CREATE TABLE replenishment_confirmation_serials (
  tenant_id BIGINT NOT NULL,
  confirmation_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  serial_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, confirmation_id, serial_id),
  KEY idx_replenishment_confirmation_serials_serial (tenant_id, serial_id),
  CONSTRAINT fk_replenishment_confirmation_serials_confirmation
    FOREIGN KEY (confirmation_id, tenant_id)
    REFERENCES replenishment_confirmations (id, tenant_id),
  CONSTRAINT fk_replenishment_confirmation_serials_serial
    FOREIGN KEY (serial_id, tenant_id, item_id)
    REFERENCES inventory_serials (id, tenant_id, item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO permissions(permission_code, module, description)
SELECT seed.permission_code, 'INVENTORY', seed.description
FROM (
  SELECT 'WMS_REPLENISHMENT_TASK_RELEASE' AS permission_code, 'Release a ready replenishment task.' AS description
  UNION ALL SELECT 'WMS_REPLENISHMENT_TASK_ASSIGN', 'Assign a released replenishment task to an active tenant member.'
  UNION ALL SELECT 'WMS_REPLENISHMENT_TASK_START', 'Start an assigned replenishment task.'
  UNION ALL SELECT 'WMS_REPLENISHMENT_TASK_CONFIRM', 'Confirm same-warehouse replenishment movements.'
  UNION ALL SELECT 'WMS_REPLENISHMENT_TASK_CANCEL', 'Cancel an unfinished replenishment task.'
  UNION ALL SELECT 'WMS_REPLENISHMENT_CONFIRMATION_REVERSE', 'Reverse a replenishment confirmation exactly once.'
) seed
WHERE NOT EXISTS (
  SELECT 1 FROM permissions p WHERE p.permission_code = seed.permission_code
);

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.permission_code IN (
  'WMS_REPLENISHMENT_TASK_RELEASE',
  'WMS_REPLENISHMENT_TASK_ASSIGN',
  'WMS_REPLENISHMENT_TASK_START',
  'WMS_REPLENISHMENT_TASK_CONFIRM',
  'WMS_REPLENISHMENT_TASK_CANCEL',
  'WMS_REPLENISHMENT_CONFIRMATION_REVERSE'
)
LEFT JOIN role_permissions rp ON rp.role_id = r.id AND rp.permission_id = p.id
WHERE r.role_code = 'TENANT_OWNER' AND r.is_system_role = 1 AND rp.role_id IS NULL;
