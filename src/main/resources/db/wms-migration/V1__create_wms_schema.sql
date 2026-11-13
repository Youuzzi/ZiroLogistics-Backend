-- ZiroLogistics WMS Database V1
-- Target: MySQL 8.4 / InnoDB / utf8mb4
-- Fresh database only. Do not run against legacy zirologistics_db.
-- Flyway location: classpath:db/wms-migration
-- This migration creates schema and deterministic reference data only.
-- Initial company/admin/role bootstrap is an application setup responsibility.

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE companies (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_code VARCHAR(50) NOT NULL,
    company_name VARCHAR(180) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NULL,
    updated_at DATETIME(6) NULL,
    updated_by BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_companies_public_id (public_id),
    UNIQUE KEY uk_companies_code (company_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(180) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NULL,
    updated_at DATETIME(6) NULL,
    updated_by BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_public_id (public_id),
    UNIQUE KEY uk_users_company_email (company_id, email),
    UNIQUE KEY uk_users_id_company (id, company_id),
    CONSTRAINT fk_users_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_users_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_users_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE companies
    ADD CONSTRAINT fk_companies_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_companies_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE RESTRICT;

CREATE TABLE roles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    role_code VARCHAR(60) NOT NULL,
    role_name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    is_system BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NULL,
    updated_at DATETIME(6) NULL,
    updated_by BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_public_id (public_id),
    UNIQUE KEY uk_roles_company_code (company_id, role_code),
    UNIQUE KEY uk_roles_id_company (id, company_id),
    CONSTRAINT fk_roles_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_roles_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_roles_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE permissions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    permission_code VARCHAR(100) NOT NULL,
    permission_name VARCHAR(140) NOT NULL,
    description VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permissions_code (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    assigned_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_roles_assignment (user_id, role_id),
    KEY ix_user_roles_company (company_id, user_id),
    CONSTRAINT fk_user_roles_user_company FOREIGN KEY (user_id, company_id) REFERENCES users(id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_roles_role_company FOREIGN KEY (role_id, company_id) REFERENCES roles(id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_user_roles_assigned_by FOREIGN KEY (assigned_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_roles_company CHECK (company_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_permissions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    granted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permissions_pair (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE warehouses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    warehouse_code VARCHAR(50) NOT NULL,
    warehouse_name VARCHAR(180) NOT NULL,
    timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    address_line VARCHAR(500) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NULL,
    updated_at DATETIME(6) NULL,
    updated_by BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouses_public_id (public_id),
    UNIQUE KEY uk_warehouses_company_code (company_id, warehouse_code),
    UNIQUE KEY uk_warehouses_id_company (id, company_id),
    CONSTRAINT fk_warehouses_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_warehouses_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_warehouses_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_warehouse_access (
    id BIGINT NOT NULL AUTO_INCREMENT,
    company_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    granted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    granted_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_warehouse_access (user_id, warehouse_id),
    CONSTRAINT fk_uwa_user_company FOREIGN KEY (user_id, company_id) REFERENCES users(id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_uwa_warehouse_company FOREIGN KEY (warehouse_id, company_id) REFERENCES warehouses(id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_uwa_granted_by FOREIGN KEY (granted_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE location_types (
    id BIGINT NOT NULL AUTO_INCREMENT,
    type_code VARCHAR(30) NOT NULL,
    type_name VARCHAR(80) NOT NULL,
    is_inventory_bearing BOOLEAN NOT NULL,
    is_pickable BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_location_types_code (type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE locations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    warehouse_id BIGINT NOT NULL,
    parent_location_id BIGINT NULL,
    location_type_id BIGINT NOT NULL,
    location_code VARCHAR(60) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    max_weight_capacity_kg DECIMAL(24,9) NULL,
    current_weight_occupancy_kg DECIMAL(24,9) NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NULL,
    updated_at DATETIME(6) NULL,
    updated_by BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_locations_public_id (public_id),
    UNIQUE KEY uk_locations_warehouse_code (warehouse_id, location_code),
    UNIQUE KEY uk_locations_id_warehouse (id, warehouse_id),
    KEY ix_locations_parent (parent_location_id),
    CONSTRAINT fk_locations_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id) ON DELETE RESTRICT,
    CONSTRAINT fk_locations_parent_same_warehouse FOREIGN KEY (parent_location_id, warehouse_id) REFERENCES locations(id, warehouse_id) ON DELETE RESTRICT,
    CONSTRAINT fk_locations_type FOREIGN KEY (location_type_id) REFERENCES location_types(id) ON DELETE RESTRICT,
    CONSTRAINT fk_locations_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_locations_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_locations_capacity CHECK (max_weight_capacity_kg IS NULL OR max_weight_capacity_kg >= 0),
    CONSTRAINT ck_locations_occupancy CHECK (current_weight_occupancy_kg >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE uoms (
    id BIGINT NOT NULL AUTO_INCREMENT,
    uom_code VARCHAR(10) NOT NULL,
    uom_name VARCHAR(60) NOT NULL,
    allowed_fraction_digits TINYINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_uoms_code (uom_code),
    CONSTRAINT ck_uoms_fraction_digits CHECK (allowed_fraction_digits <= 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    base_uom_id BIGINT NOT NULL,
    sku VARCHAR(80) NOT NULL,
    item_name VARCHAR(180) NOT NULL,
    description VARCHAR(1000) NULL,
    weight_per_base_uom_kg DECIMAL(18,6) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NULL,
    updated_at DATETIME(6) NULL,
    updated_by BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_items_public_id (public_id),
    UNIQUE KEY uk_items_company_sku (company_id, sku),
    UNIQUE KEY uk_items_id_company (id, company_id),
    CONSTRAINT fk_items_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_items_base_uom FOREIGN KEY (base_uom_id) REFERENCES uoms(id) ON DELETE RESTRICT,
    CONSTRAINT fk_items_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_items_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_items_weight CHECK (weight_per_base_uom_kg IS NULL OR weight_per_base_uom_kg > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_statuses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    status_code VARCHAR(30) NOT NULL,
    status_name VARCHAR(80) NOT NULL,
    is_normally_allocatable BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_statuses_code (status_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_transactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    transaction_number VARCHAR(80) NOT NULL,
    transaction_type VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'POSTED',
    reference_note VARCHAR(500) NULL,
    posted_at DATETIME(6) NOT NULL,
    posted_by BIGINT NOT NULL,
    reverses_transaction_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_transactions_public_id (public_id),
    UNIQUE KEY uk_inventory_transactions_number (company_id, transaction_number),
    UNIQUE KEY uk_inventory_transactions_idempotency (company_id, idempotency_key),
    UNIQUE KEY uk_inventory_transactions_id_company (id, company_id),
    KEY ix_inventory_transactions_posted_at (company_id, posted_at),
    CONSTRAINT fk_inventory_transactions_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_transactions_actor FOREIGN KEY (posted_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_transactions_reversal FOREIGN KEY (reverses_transaction_id) REFERENCES inventory_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_inventory_transactions_status CHECK (status = 'POSTED'),
    CONSTRAINT ck_inventory_transactions_hash CHECK (CHAR_LENGTH(payload_hash) = 64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_transaction_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id BIGINT NOT NULL,
    line_number INT UNSIGNED NOT NULL,
    item_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    inventory_status_id BIGINT NOT NULL,
    quantity_delta DECIMAL(18,3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_transaction_lines_number (transaction_id, line_number),
    UNIQUE KEY uk_inventory_transaction_lines_id_transaction (id, transaction_id),
    KEY ix_inventory_transaction_lines_item_location (item_id, location_id),
    CONSTRAINT fk_itl_transaction FOREIGN KEY (transaction_id) REFERENCES inventory_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_itl_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_itl_location FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_itl_status FOREIGN KEY (inventory_status_id) REFERENCES inventory_statuses(id) ON DELETE RESTRICT,
    CONSTRAINT ck_itl_nonzero_delta CHECK (quantity_delta <> 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_balances (
    id BIGINT NOT NULL AUTO_INCREMENT,
    item_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    inventory_status_id BIGINT NOT NULL,
    on_hand_quantity DECIMAL(18,3) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_balances_identity (item_id, location_id, inventory_status_id),
    UNIQUE KEY uk_inventory_balances_id_item (id, item_id),
    KEY ix_inventory_balances_location (location_id),
    CONSTRAINT fk_ib_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ib_location FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ib_status FOREIGN KEY (inventory_status_id) REFERENCES inventory_statuses(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ib_on_hand CHECK (on_hand_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inbound_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    receipt_number VARCHAR(80) NOT NULL,
    source_reference VARCHAR(120) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    expected_at DATETIME(6) NULL,
    notes VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NOT NULL,
    updated_at DATETIME(6) NULL,
    updated_by BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbound_receipts_public_id (public_id),
    UNIQUE KEY uk_inbound_receipts_number (company_id, receipt_number),
    UNIQUE KEY uk_inbound_receipts_id_company (id, company_id),
    CONSTRAINT fk_ir_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ir_warehouse_company FOREIGN KEY (warehouse_id, company_id) REFERENCES warehouses(id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_ir_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ir_updated_by FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ir_status CHECK (status IN ('DRAFT','IN_PROGRESS','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inbound_receipt_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    receipt_id BIGINT NOT NULL,
    line_number INT UNSIGNED NOT NULL,
    item_id BIGINT NOT NULL,
    expected_quantity DECIMAL(18,3) NOT NULL,
    notes VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbound_receipt_lines_public_id (public_id),
    UNIQUE KEY uk_inbound_receipt_lines_number (receipt_id, line_number),
    UNIQUE KEY uk_inbound_receipt_lines_id_receipt (id, receipt_id),
    CONSTRAINT fk_irl_receipt FOREIGN KEY (receipt_id) REFERENCES inbound_receipts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_irl_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT,
    CONSTRAINT ck_irl_expected CHECK (expected_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE receiving_confirmations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    receipt_id BIGINT NOT NULL,
    inventory_transaction_id BIGINT NOT NULL,
    confirmation_number VARCHAR(80) NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    confirmed_by BIGINT NOT NULL,
    notes VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_receiving_confirmations_public_id (public_id),
    UNIQUE KEY uk_receiving_confirmations_number (confirmation_number),
    UNIQUE KEY uk_receiving_confirmations_transaction (inventory_transaction_id),
    UNIQUE KEY uk_receiving_confirmations_id_receipt (id, receipt_id),
    CONSTRAINT fk_rc_receipt FOREIGN KEY (receipt_id) REFERENCES inbound_receipts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rc_transaction FOREIGN KEY (inventory_transaction_id) REFERENCES inventory_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rc_actor FOREIGN KEY (confirmed_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE receiving_confirmation_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    confirmation_id BIGINT NOT NULL,
    receipt_id BIGINT NOT NULL,
    receipt_line_id BIGINT NOT NULL,
    inventory_transaction_line_id BIGINT NOT NULL,
    accepted_quantity DECIMAL(18,3) NOT NULL,
    rejected_quantity DECIMAL(18,3) NOT NULL DEFAULT 0,
    inventory_status_id BIGINT NOT NULL,
    receiving_location_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_receiving_confirmation_tx_line (inventory_transaction_line_id),
    CONSTRAINT fk_rcl_confirmation_receipt FOREIGN KEY (confirmation_id, receipt_id) REFERENCES receiving_confirmations(id, receipt_id) ON DELETE RESTRICT,
    CONSTRAINT fk_rcl_receipt_line FOREIGN KEY (receipt_line_id, receipt_id) REFERENCES inbound_receipt_lines(id, receipt_id) ON DELETE RESTRICT,
    CONSTRAINT fk_rcl_tx_line FOREIGN KEY (inventory_transaction_line_id) REFERENCES inventory_transaction_lines(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rcl_status FOREIGN KEY (inventory_status_id) REFERENCES inventory_statuses(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rcl_location FOREIGN KEY (receiving_location_id) REFERENCES locations(id) ON DELETE RESTRICT,
    CONSTRAINT ck_rcl_quantities CHECK (accepted_quantity >= 0 AND rejected_quantity >= 0 AND accepted_quantity + rejected_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE putaway_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    task_number VARCHAR(80) NOT NULL,
    source_location_id BIGINT NOT NULL,
    destination_location_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    inventory_status_id BIGINT NOT NULL,
    planned_quantity DECIMAL(18,3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_putaway_tasks_public_id (public_id),
    UNIQUE KEY uk_putaway_tasks_number (company_id, task_number),
    CONSTRAINT fk_pt_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pt_warehouse_company FOREIGN KEY (warehouse_id, company_id) REFERENCES warehouses(id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_pt_source FOREIGN KEY (source_location_id, warehouse_id) REFERENCES locations(id, warehouse_id) ON DELETE RESTRICT,
    CONSTRAINT fk_pt_destination FOREIGN KEY (destination_location_id, warehouse_id) REFERENCES locations(id, warehouse_id) ON DELETE RESTRICT,
    CONSTRAINT fk_pt_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pt_status FOREIGN KEY (inventory_status_id) REFERENCES inventory_statuses(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pt_actor FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_pt_quantity CHECK (planned_quantity > 0),
    CONSTRAINT ck_pt_locations CHECK (source_location_id <> destination_location_id),
    CONSTRAINT ck_pt_status CHECK (status IN ('OPEN','IN_PROGRESS','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE putaway_confirmations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    task_id BIGINT NOT NULL,
    inventory_transaction_id BIGINT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    confirmed_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_putaway_confirmations_public_id (public_id),
    UNIQUE KEY uk_putaway_confirmations_transaction (inventory_transaction_id),
    CONSTRAINT fk_pc_task FOREIGN KEY (task_id) REFERENCES putaway_tasks(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pc_transaction FOREIGN KEY (inventory_transaction_id) REFERENCES inventory_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pc_actor FOREIGN KEY (confirmed_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE putaway_confirmation_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    confirmation_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    source_transaction_line_id BIGINT NOT NULL,
    destination_transaction_line_id BIGINT NOT NULL,
    executed_quantity DECIMAL(18,3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_putaway_source_tx_line (source_transaction_line_id),
    UNIQUE KEY uk_putaway_destination_tx_line (destination_transaction_line_id),
    CONSTRAINT fk_pcl_confirmation FOREIGN KEY (confirmation_id) REFERENCES putaway_confirmations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pcl_task FOREIGN KEY (task_id) REFERENCES putaway_tasks(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pcl_source_line FOREIGN KEY (source_transaction_line_id) REFERENCES inventory_transaction_lines(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pcl_destination_line FOREIGN KEY (destination_transaction_line_id) REFERENCES inventory_transaction_lines(id) ON DELETE RESTRICT,
    CONSTRAINT ck_pcl_quantity CHECK (executed_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE stock_transfers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    source_warehouse_id BIGINT NOT NULL,
    destination_warehouse_id BIGINT NOT NULL,
    transfer_number VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    notes VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_transfers_public_id (public_id),
    UNIQUE KEY uk_stock_transfers_number (company_id, transfer_number),
    CONSTRAINT fk_st_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_st_source_warehouse FOREIGN KEY (source_warehouse_id, company_id) REFERENCES warehouses(id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_st_destination_warehouse FOREIGN KEY (destination_warehouse_id, company_id) REFERENCES warehouses(id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_st_actor FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_st_warehouses CHECK (source_warehouse_id <> destination_warehouse_id),
    CONSTRAINT ck_st_status CHECK (status IN ('DRAFT','CONFIRMED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE stock_transfer_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    transfer_id BIGINT NOT NULL,
    line_number INT UNSIGNED NOT NULL,
    item_id BIGINT NOT NULL,
    source_location_id BIGINT NOT NULL,
    destination_location_id BIGINT NOT NULL,
    inventory_status_id BIGINT NOT NULL,
    requested_quantity DECIMAL(18,3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_transfer_lines_public_id (public_id),
    UNIQUE KEY uk_stock_transfer_lines_number (transfer_id, line_number),
    CONSTRAINT fk_stl_transfer FOREIGN KEY (transfer_id) REFERENCES stock_transfers(id) ON DELETE RESTRICT,
    CONSTRAINT fk_stl_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_stl_source FOREIGN KEY (source_location_id) REFERENCES locations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_stl_destination FOREIGN KEY (destination_location_id) REFERENCES locations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_stl_status FOREIGN KEY (inventory_status_id) REFERENCES inventory_statuses(id) ON DELETE RESTRICT,
    CONSTRAINT ck_stl_quantity CHECK (requested_quantity > 0),
    CONSTRAINT ck_stl_locations CHECK (source_location_id <> destination_location_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE transfer_confirmations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    transfer_id BIGINT NOT NULL,
    inventory_transaction_id BIGINT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    confirmed_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transfer_confirmations_public_id (public_id),
    UNIQUE KEY uk_transfer_confirmations_transaction (inventory_transaction_id),
    CONSTRAINT fk_tc_transfer FOREIGN KEY (transfer_id) REFERENCES stock_transfers(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tc_transaction FOREIGN KEY (inventory_transaction_id) REFERENCES inventory_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tc_actor FOREIGN KEY (confirmed_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE transfer_confirmation_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    confirmation_id BIGINT NOT NULL,
    transfer_line_id BIGINT NOT NULL,
    source_transaction_line_id BIGINT NOT NULL,
    destination_transaction_line_id BIGINT NOT NULL,
    executed_quantity DECIMAL(18,3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tcl_transfer_line (confirmation_id, transfer_line_id),
    UNIQUE KEY uk_tcl_source_tx_line (source_transaction_line_id),
    UNIQUE KEY uk_tcl_destination_tx_line (destination_transaction_line_id),
    CONSTRAINT fk_tcl_confirmation FOREIGN KEY (confirmation_id) REFERENCES transfer_confirmations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tcl_transfer_line FOREIGN KEY (transfer_line_id) REFERENCES stock_transfer_lines(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tcl_source_line FOREIGN KEY (source_transaction_line_id) REFERENCES inventory_transaction_lines(id) ON DELETE RESTRICT,
    CONSTRAINT fk_tcl_destination_line FOREIGN KEY (destination_transaction_line_id) REFERENCES inventory_transaction_lines(id) ON DELETE RESTRICT,
    CONSTRAINT ck_tcl_quantity CHECK (executed_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE stock_counts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    count_number VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    snapshot_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NOT NULL,
    approved_at DATETIME(6) NULL,
    approved_by BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_counts_public_id (public_id),
    UNIQUE KEY uk_stock_counts_number (company_id, count_number),
    CONSTRAINT fk_sc_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sc_warehouse FOREIGN KEY (warehouse_id, company_id) REFERENCES warehouses(id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_sc_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sc_approved_by FOREIGN KEY (approved_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_sc_status CHECK (status IN ('DRAFT','IN_PROGRESS','PENDING_APPROVAL','APPROVED','POSTED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE stock_count_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    stock_count_id BIGINT NOT NULL,
    line_number INT UNSIGNED NOT NULL,
    item_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    inventory_status_id BIGINT NOT NULL,
    expected_quantity DECIMAL(18,3) NOT NULL,
    counted_quantity DECIMAL(18,3) NULL,
    counted_at DATETIME(6) NULL,
    counted_by BIGINT NULL,
    notes VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_count_lines_public_id (public_id),
    UNIQUE KEY uk_stock_count_lines_number (stock_count_id, line_number),
    CONSTRAINT fk_scl_count FOREIGN KEY (stock_count_id) REFERENCES stock_counts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_scl_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_scl_location FOREIGN KEY (location_id) REFERENCES locations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_scl_status FOREIGN KEY (inventory_status_id) REFERENCES inventory_statuses(id) ON DELETE RESTRICT,
    CONSTRAINT fk_scl_actor FOREIGN KEY (counted_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_scl_expected CHECK (expected_quantity >= 0),
    CONSTRAINT ck_scl_counted CHECK (counted_quantity IS NULL OR counted_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE stock_adjustments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    stock_count_id BIGINT NULL,
    company_id BIGINT NOT NULL,
    adjustment_number VARCHAR(80) NOT NULL,
    inventory_transaction_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    approved_at DATETIME(6) NOT NULL,
    approved_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_adjustments_public_id (public_id),
    UNIQUE KEY uk_stock_adjustments_number (company_id, adjustment_number),
    UNIQUE KEY uk_stock_adjustments_transaction (inventory_transaction_id),
    CONSTRAINT fk_sa_count FOREIGN KEY (stock_count_id) REFERENCES stock_counts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sa_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sa_transaction FOREIGN KEY (inventory_transaction_id) REFERENCES inventory_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sa_approver FOREIGN KEY (approved_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE stock_adjustment_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    adjustment_id BIGINT NOT NULL,
    stock_count_line_id BIGINT NULL,
    inventory_transaction_line_id BIGINT NOT NULL,
    adjustment_quantity DECIMAL(18,3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sal_tx_line (inventory_transaction_line_id),
    CONSTRAINT fk_sal_adjustment FOREIGN KEY (adjustment_id) REFERENCES stock_adjustments(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sal_count_line FOREIGN KEY (stock_count_line_id) REFERENCES stock_count_lines(id) ON DELETE RESTRICT,
    CONSTRAINT fk_sal_tx_line FOREIGN KEY (inventory_transaction_line_id) REFERENCES inventory_transaction_lines(id) ON DELETE RESTRICT,
    CONSTRAINT ck_sal_quantity CHECK (adjustment_quantity <> 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE outbound_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    order_number VARCHAR(80) NOT NULL,
    external_reference VARCHAR(120) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    requested_by BIGINT NOT NULL,
    notes VARCHAR(1000) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbound_orders_public_id (public_id),
    UNIQUE KEY uk_outbound_orders_number (company_id, order_number),
    UNIQUE KEY uk_outbound_orders_id_company (id, company_id),
    CONSTRAINT fk_oo_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_oo_warehouse FOREIGN KEY (warehouse_id, company_id) REFERENCES warehouses(id, company_id) ON DELETE RESTRICT,
    CONSTRAINT fk_oo_actor FOREIGN KEY (requested_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_oo_status CHECK (status IN ('DRAFT','OPEN','ALLOCATED','IN_PROGRESS','PARTIALLY_SHIPPED','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE outbound_order_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    outbound_order_id BIGINT NOT NULL,
    line_number INT UNSIGNED NOT NULL,
    item_id BIGINT NOT NULL,
    requested_quantity DECIMAL(18,3) NOT NULL,
    notes VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbound_order_lines_public_id (public_id),
    UNIQUE KEY uk_outbound_order_lines_number (outbound_order_id, line_number),
    UNIQUE KEY uk_outbound_order_lines_id_order (id, outbound_order_id),
    CONSTRAINT fk_ool_order FOREIGN KEY (outbound_order_id) REFERENCES outbound_orders(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ool_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ool_quantity CHECK (requested_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_reservations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    outbound_order_line_id BIGINT NOT NULL,
    inventory_balance_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    reserved_quantity DECIMAL(18,3) NOT NULL,
    consumed_quantity DECIMAL(18,3) NOT NULL DEFAULT 0,
    released_quantity DECIMAL(18,3) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    reserved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reserved_by BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_reservations_public_id (public_id),
    KEY ix_inventory_reservations_balance_status (inventory_balance_id, status),
    CONSTRAINT fk_res_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_res_order_line FOREIGN KEY (outbound_order_line_id) REFERENCES outbound_order_lines(id) ON DELETE RESTRICT,
    CONSTRAINT fk_res_balance_item FOREIGN KEY (inventory_balance_id, item_id) REFERENCES inventory_balances(id, item_id) ON DELETE RESTRICT,
    CONSTRAINT fk_res_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_res_actor FOREIGN KEY (reserved_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_res_quantities CHECK (reserved_quantity > 0 AND consumed_quantity >= 0 AND released_quantity >= 0 AND consumed_quantity + released_quantity <= reserved_quantity),
    CONSTRAINT ck_res_status CHECK (status IN ('ACTIVE','PARTIALLY_CONSUMED','CONSUMED','RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pick_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    outbound_order_id BIGINT NOT NULL,
    outbound_order_line_id BIGINT NOT NULL,
    inventory_reservation_id BIGINT NOT NULL,
    source_location_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    inventory_status_id BIGINT NOT NULL,
    planned_quantity DECIMAL(18,3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pick_tasks_public_id (public_id),
    CONSTRAINT fk_pick_order FOREIGN KEY (outbound_order_id) REFERENCES outbound_orders(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_order_line FOREIGN KEY (outbound_order_line_id) REFERENCES outbound_order_lines(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_reservation FOREIGN KEY (inventory_reservation_id) REFERENCES inventory_reservations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_source_location FOREIGN KEY (source_location_id) REFERENCES locations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_status FOREIGN KEY (inventory_status_id) REFERENCES inventory_statuses(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_actor FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_pick_quantity CHECK (planned_quantity > 0),
    CONSTRAINT ck_pick_status CHECK (status IN ('OPEN','IN_PROGRESS','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pick_confirmations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    outbound_order_id BIGINT NOT NULL,
    inventory_transaction_id BIGINT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    confirmed_by BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pick_confirmations_public_id (public_id),
    UNIQUE KEY uk_pick_confirmations_transaction (inventory_transaction_id),
    CONSTRAINT fk_pick_conf_order FOREIGN KEY (outbound_order_id) REFERENCES outbound_orders(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_conf_transaction FOREIGN KEY (inventory_transaction_id) REFERENCES inventory_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_conf_actor FOREIGN KEY (confirmed_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pick_confirmation_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    confirmation_id BIGINT NOT NULL,
    pick_task_id BIGINT NOT NULL,
    inventory_reservation_id BIGINT NOT NULL,
    source_transaction_line_id BIGINT NOT NULL,
    picking_transaction_line_id BIGINT NOT NULL,
    picked_quantity DECIMAL(18,3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pick_source_tx_line (source_transaction_line_id),
    UNIQUE KEY uk_pick_destination_tx_line (picking_transaction_line_id),
    CONSTRAINT fk_pcl_confirmation FOREIGN KEY (confirmation_id) REFERENCES pick_confirmations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pcl_task FOREIGN KEY (pick_task_id) REFERENCES pick_tasks(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pcl_reservation FOREIGN KEY (inventory_reservation_id) REFERENCES inventory_reservations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pcl_source_line FOREIGN KEY (source_transaction_line_id) REFERENCES inventory_transaction_lines(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pcl_picking_line FOREIGN KEY (picking_transaction_line_id) REFERENCES inventory_transaction_lines(id) ON DELETE RESTRICT,
    CONSTRAINT ck_pcl_picked CHECK (picked_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shipments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    outbound_order_id BIGINT NOT NULL,
    shipment_number VARCHAR(80) NOT NULL,
    inventory_transaction_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DISPATCHED',
    dispatched_at DATETIME(6) NOT NULL,
    dispatched_by BIGINT NOT NULL,
    carrier_reference VARCHAR(120) NULL,
    notes VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipments_public_id (public_id),
    UNIQUE KEY uk_shipments_number (shipment_number),
    UNIQUE KEY uk_shipments_transaction (inventory_transaction_id),
    CONSTRAINT fk_ship_order FOREIGN KEY (outbound_order_id) REFERENCES outbound_orders(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ship_transaction FOREIGN KEY (inventory_transaction_id) REFERENCES inventory_transactions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ship_actor FOREIGN KEY (dispatched_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ship_status CHECK (status = 'DISPATCHED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shipment_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shipment_id BIGINT NOT NULL,
    outbound_order_line_id BIGINT NOT NULL,
    dispatch_transaction_line_id BIGINT NOT NULL,
    dispatched_quantity DECIMAL(18,3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_dispatch_tx_line (dispatch_transaction_line_id),
    CONSTRAINT fk_shipment_lines_shipment FOREIGN KEY (shipment_id) REFERENCES shipments(id) ON DELETE RESTRICT,
    CONSTRAINT fk_shipment_lines_order_line FOREIGN KEY (outbound_order_line_id) REFERENCES outbound_order_lines(id) ON DELETE RESTRICT,
    CONSTRAINT fk_shipment_lines_tx_line FOREIGN KEY (dispatch_transaction_line_id) REFERENCES inventory_transaction_lines(id) ON DELETE RESTRICT,
    CONSTRAINT ck_shipment_lines_quantity CHECK (dispatched_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id CHAR(36) NOT NULL,
    company_id BIGINT NOT NULL,
    actor_user_id BIGINT NULL,
    event_type VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_public_id CHAR(36) NOT NULL,
    event_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    correlation_id CHAR(36) NULL,
    details JSON NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_events_public_id (public_id),
    KEY ix_audit_events_company_time (company_id, event_at),
    KEY ix_audit_events_entity (company_id, entity_type, entity_public_id),
    CONSTRAINT fk_audit_events_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_events_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Deterministic controlled reference data. No company, user, role, password, or secret is seeded.
INSERT INTO location_types (type_code, type_name, is_inventory_bearing, is_pickable) VALUES
    ('RECEIVING', 'Receiving', TRUE, FALSE),
    ('STAGING', 'Staging', TRUE, TRUE),
    ('STORAGE', 'Storage', TRUE, TRUE),
    ('PICKING', 'Picking', TRUE, FALSE),
    ('DISPATCH', 'Dispatch', TRUE, FALSE),
    ('QUARANTINE', 'Quarantine', TRUE, FALSE);

INSERT INTO uoms (uom_code, uom_name, allowed_fraction_digits) VALUES
    ('PCS', 'Pieces', 0),
    ('BOX', 'Box', 0),
    ('KG', 'Kilogram', 3),
    ('GR', 'Gram', 0),
    ('ML', 'Millilitre', 0);

INSERT INTO inventory_statuses (status_code, status_name, is_normally_allocatable) VALUES
    ('AVAILABLE', 'Available', TRUE),
    ('DAMAGED', 'Damaged', FALSE),
    ('QUARANTINED', 'Quarantined', FALSE);

-- Permissions are stable capability identifiers, not business role assignments.
INSERT INTO permissions (permission_code, permission_name, description) VALUES
    ('warehouse.read', 'View warehouses', 'View warehouses and warehouse details'),
    ('warehouse.manage', 'Manage warehouses', 'Create and update warehouse master data'),
    ('location.read', 'View locations', 'View warehouse locations'),
    ('location.manage', 'Manage locations', 'Create and update warehouse locations'),
    ('item.read', 'View items', 'View item master data'),
    ('item.manage', 'Manage items', 'Create and update item master data'),
    ('inventory.read', 'View inventory', 'View inventory balances and history'),
    ('inventory.post', 'Post inventory', 'Post inventory movements'),
    ('inbound.manage', 'Manage inbound', 'Manage receiving and putaway workflows'),
    ('transfer.manage', 'Manage transfers', 'Create and confirm stock transfers'),
    ('count.manage', 'Manage stock counts', 'Perform and approve stock counts'),
    ('outbound.manage', 'Manage outbound', 'Manage reservations, picking and dispatch'),
    ('user.manage', 'Manage users', 'Manage users, roles and warehouse access'),
    ('audit.read', 'View audit events', 'View audit event history');

-- Application-enforced invariants (must be covered by integration tests):
-- * cross-company item/location/document ownership checks where not expressible by these FKs;
-- * location type rules, parent-child stock rules, hierarchy cycle prevention;
-- * UOM-specific quantity scale and item weight semantics;
-- * inventory balance projection updates, no-negative-stock, reservations and capacity concurrency;
-- * source/target line sign, item, location, status and quantity consistency for every posting;
-- * one company per user/role assignment and actor authorization at the selected warehouse;
-- * immutable posted transactions and compensating-only reversals;
-- * confirmation/document state transitions and cumulative partial execution limits;
-- * idempotency payload hash equality and transactional posting/rollback behavior.
