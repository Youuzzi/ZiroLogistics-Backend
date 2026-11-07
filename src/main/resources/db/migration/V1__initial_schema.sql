-- Initial schema for a fresh ZiroLogistics database.
-- This migration intentionally does not attempt to adopt or modify an existing database.
-- Existing installations require a separately reviewed Flyway baseline/adoption procedure.

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) DEFAULT NULL,
    created_by VARCHAR(255) DEFAULT NULL,
    public_id VARCHAR(255) NOT NULL,
    updated_at DATETIME(6) DEFAULT NULL,
    updated_by VARCHAR(255) DEFAULT NULL,
    version BIGINT DEFAULT NULL,
    active BIT(1) NOT NULL,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_public_id (public_id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE warehouses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(255) NOT NULL,
    code VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(255) DEFAULT NULL,
    is_deleted TINYINT(1) DEFAULT '0',
    version BIGINT DEFAULT '0',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(255) DEFAULT NULL,
    updated_by VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouses_public_id (public_id),
    UNIQUE KEY uk_warehouses_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(255) NOT NULL,
    sku VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    base_uom VARCHAR(255) NOT NULL,
    min_stock_level DECIMAL(38,2) NOT NULL,
    weight_per_unit DECIMAL(38,2) NOT NULL,
    is_deleted TINYINT(1) DEFAULT '0',
    version BIGINT DEFAULT '0',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(255) DEFAULT NULL,
    updated_by VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_items_public_id (public_id),
    UNIQUE KEY uk_items_sku (sku),
    KEY idx_item_sku (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bins (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(255) NOT NULL,
    warehouse_id BIGINT NOT NULL,
    zone_name VARCHAR(255) DEFAULT NULL,
    rack_number VARCHAR(255) DEFAULT NULL,
    bin_code VARCHAR(255) NOT NULL,
    is_deleted TINYINT(1) DEFAULT '0',
    version BIGINT DEFAULT '0',
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(255) DEFAULT NULL,
    updated_by VARCHAR(255) DEFAULT NULL,
    current_weight_occupancy DECIMAL(38,2) NOT NULL,
    max_weight_capacity DECIMAL(38,2) NOT NULL,
    min_weight_threshold DECIMAL(38,2) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bins_public_id (public_id),
    UNIQUE KEY uk_bins_bin_code (bin_code),
    KEY idx_bin_warehouse (warehouse_id),
    CONSTRAINT fk_bins_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_stocks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(255) NOT NULL,
    item_id BIGINT NOT NULL,
    bin_id BIGINT NOT NULL,
    quantity DECIMAL(38,2) NOT NULL,
    status VARCHAR(255) NOT NULL,
    version BIGINT DEFAULT '0',
    last_stock_count TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by VARCHAR(255) DEFAULT NULL,
    updated_by VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_stocks_public_id (public_id),
    UNIQUE KEY uq_item_bin_status (item_id, bin_id, status),
    KEY idx_inventory_stocks_bin (bin_id),
    KEY idx_stock_status (status),
    CONSTRAINT fk_inventory_stocks_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_inventory_stocks_bin FOREIGN KEY (bin_id) REFERENCES bins (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE inventory_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(255) NOT NULL,
    item_id BIGINT NOT NULL,
    bin_id BIGINT NOT NULL,
    transaction_type VARCHAR(255) DEFAULT NULL,
    quantity_change DECIMAL(38,2) DEFAULT NULL,
    balance_before DECIMAL(38,2) DEFAULT NULL,
    balance_after DECIMAL(38,2) DEFAULT NULL,
    reference_no VARCHAR(255) DEFAULT NULL,
    request_id VARCHAR(255) DEFAULT NULL,
    note VARCHAR(255) DEFAULT NULL,
    user_id VARCHAR(255) DEFAULT NULL,
    created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255) DEFAULT NULL,
    updated_at DATETIME(6) DEFAULT NULL,
    updated_by VARCHAR(255) DEFAULT NULL,
    version BIGINT DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_ledger_public_id (public_id),
    UNIQUE KEY uk_inventory_ledger_request_id (request_id),
    KEY idx_inventory_ledger_bin (bin_id),
    KEY idx_ledger_history (item_id, bin_id, created_at),
    KEY idx_ledger_ref (reference_no),
    CONSTRAINT fk_inventory_ledger_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_inventory_ledger_bin FOREIGN KEY (bin_id) REFERENCES bins (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
