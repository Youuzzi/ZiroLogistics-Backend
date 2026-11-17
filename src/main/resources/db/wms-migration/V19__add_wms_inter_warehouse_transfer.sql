-- Inter-warehouse transfer lifecycle extensions. V1-V18 remain immutable.
-- Transit is represented by transfer documents/allocations and serial lifecycle, never a virtual location.

ALTER TABLE inventory_ledger DROP CHECK chk_inventory_ledger_movement_type;
ALTER TABLE inventory_ledger ADD CONSTRAINT chk_inventory_ledger_movement_type CHECK (
  movement_type IN (
    'RECEIPT','ISSUE','TRANSFER','TRANSFER_DISPATCH','TRANSFER_RECEIPT',
    'ADJUSTMENT_IN','ADJUSTMENT_OUT','RETURN','DAMAGE','STATUS_CHANGE','OPENING_BALANCE'
  )
);

ALTER TABLE inventory_serials DROP CHECK chk_inventory_serials_status;
ALTER TABLE inventory_serials ADD CONSTRAINT chk_inventory_serials_status CHECK (
  status IN ('AVAILABLE','RESERVED','PICKED','IN_TRANSIT','SHIPPED','DAMAGED','QUARANTINED','RETURNED','LOST')
);

ALTER TABLE stock_transfers ADD UNIQUE KEY uk_stock_transfers_id_tenant (id, tenant_id);

ALTER TABLE stock_transfer_allocations
  ADD COLUMN public_id CHAR(36) NULL AFTER id,
  ADD COLUMN short_qty DECIMAL(19,4) NOT NULL DEFAULT 0.0000 AFTER damaged_qty;
UPDATE stock_transfer_allocations SET public_id = UUID() WHERE public_id IS NULL;
ALTER TABLE stock_transfer_allocations MODIFY public_id CHAR(36) NOT NULL;
ALTER TABLE stock_transfer_allocations ADD UNIQUE KEY uk_stock_transfer_allocations_public_id (public_id);

CREATE TABLE stock_transfer_commands (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  stock_transfer_id BIGINT NOT NULL,
  request_id CHAR(36) NOT NULL,
  command_type VARCHAR(30) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_transfer_commands_request (tenant_id, request_id),
  KEY idx_stock_transfer_commands_document (tenant_id, stock_transfer_id, created_at),
  CONSTRAINT fk_stock_transfer_commands_transfer FOREIGN KEY (stock_transfer_id, tenant_id)
    REFERENCES stock_transfers (id, tenant_id) ON DELETE RESTRICT,
  CONSTRAINT chk_stock_transfer_commands_type CHECK (command_type IN ('RELEASE','DISPATCH','RECEIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE stock_transfer_lines DROP CHECK chk_stock_transfer_lines_quantities;
ALTER TABLE stock_transfer_lines ADD CONSTRAINT chk_stock_transfer_lines_quantities CHECK (
  requested_qty > 0 AND dispatched_qty >= 0 AND received_qty >= 0 AND damaged_qty >= 0 AND short_qty >= 0
  AND received_qty + damaged_qty + short_qty <= dispatched_qty
  AND dispatched_qty <= requested_qty
);

ALTER TABLE stock_transfer_allocations DROP CHECK chk_stock_transfer_allocations_qty;
ALTER TABLE stock_transfer_allocations ADD CONSTRAINT chk_stock_transfer_allocations_qty CHECK (
  planned_qty > 0
  AND dispatched_qty >= 0
  AND received_qty >= 0
  AND damaged_qty >= 0
  AND short_qty >= 0
  AND received_qty + damaged_qty + short_qty <= dispatched_qty
  AND dispatched_qty <= planned_qty
);

ALTER TABLE stock_transfer_line_serials DROP CHECK chk_stock_transfer_line_serials_status;
ALTER TABLE stock_transfer_line_serials ADD CONSTRAINT chk_stock_transfer_line_serials_status CHECK (
  status IN ('PLANNED','DISPATCHED','IN_TRANSIT','RECEIVED','DAMAGED','LOST','CANCELLED')
);

ALTER TABLE tenant_audit_events DROP CHECK chk_tenant_audit_events_type;
ALTER TABLE tenant_audit_events ADD CONSTRAINT chk_tenant_audit_events_type CHECK (event_type IN (
'TENANT_PROFILE_UPDATED','TENANT_SUSPENDED','TENANT_REACTIVATED','TENANT_CLOSED','TENANT_PROVISIONED',
'ROLE_CREATED','ROLE_UPDATED','ROLE_DEACTIVATED','ROLE_PERMISSIONS_REPLACED','MEMBERSHIP_ROLE_ASSIGNED','MEMBERSHIP_ROLE_REMOVED',
'WAREHOUSE_CREATED','WAREHOUSE_UPDATED','WAREHOUSE_STATUS_CHANGED','WAREHOUSE_LOCATION_CREATED','WAREHOUSE_LOCATION_UPDATED','WAREHOUSE_LOCATION_STATUS_CHANGED',
'ITEM_CATEGORY_CREATED','ITEM_CATEGORY_UPDATED','ITEM_CATEGORY_STATUS_CHANGED','ITEM_CREATED','ITEM_UPDATED','ITEM_STATUS_CHANGED',
'ITEM_IDENTIFIER_ADDED','ITEM_IDENTIFIER_UPDATED','ITEM_IDENTIFIER_REMOVED','ITEM_UOM_CONVERSION_CREATED','ITEM_UOM_CONVERSION_DEACTIVATED',
'INVENTORY_LOT_CREATED','INVENTORY_LOT_STATUS_CHANGED','INVENTORY_SERIAL_REGISTERED','INVENTORY_SERIAL_STATUS_CHANGED','INVENTORY_MOVEMENT_POSTED',
'INBOUND_RECEIPT_CREATED','INBOUND_RECEIPT_LINE_ADDED','INBOUND_RECEIPT_POSTED',
'PUTAWAY_TASK_CREATED','PUTAWAY_TASK_READY','PUTAWAY_TASK_ASSIGNED','PUTAWAY_TASK_STARTED','PUTAWAY_CONFIRMED','PUTAWAY_TASK_COMPLETED',
'OUTBOUND_SHIPMENT_CREATED','OUTBOUND_SHIPMENT_LINE_ADDED','OUTBOUND_SHIPMENT_ALLOCATED','OUTBOUND_SHIPMENT_RELEASED',
'PICKING_TASK_CREATED','PICKING_TASK_ASSIGNED','PICKING_TASK_STARTED','PICKING_CONFIRMED',
'PACKING_SESSION_CREATED','PACKING_SESSION_READY','PACKING_SESSION_ASSIGNED','PACKING_SESSION_STARTED',
'PACKING_PACKAGE_CREATED','PACKING_PACKAGE_LINE_ADDED','PACKING_PACKAGE_SEALED','PACKING_SESSION_COMPLETED',
'OUTBOUND_SHIPMENT_SHIPPED','WMS_STOCK_TRANSFER_UPDATED'
));

INSERT INTO permissions(permission_code,module,description) VALUES
('WMS_TRANSFER_READ','WAREHOUSE','Read tenant-scoped inter-warehouse transfers.'),
('WMS_TRANSFER_CREATE','WAREHOUSE','Create transfers and define item/location/lot allocations.'),
('WMS_TRANSFER_RELEASE','WAREHOUSE','Validate and release a planned inter-warehouse transfer.'),
('WMS_TRANSFER_DISPATCH','WAREHOUSE','Dispatch stock from the source warehouse into transit.'),
('WMS_TRANSFER_RECEIVE','WAREHOUSE','Receive, classify damaged goods and reconcile shortages at destination.');

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r JOIN permissions p ON p.permission_code IN
('WMS_TRANSFER_READ','WMS_TRANSFER_CREATE','WMS_TRANSFER_RELEASE','WMS_TRANSFER_DISPATCH','WMS_TRANSFER_RECEIVE')
WHERE r.role_code='TENANT_OWNER' AND r.is_system_role=1;
