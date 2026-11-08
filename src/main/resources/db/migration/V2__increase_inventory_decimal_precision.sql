-- Widen inventory quantities and physical-weight values to preserve KG precision.
-- This is an additive precision expansion; existing numeric values are preserved.
ALTER TABLE items
    MODIFY COLUMN min_stock_level DECIMAL(38,6) NOT NULL,
    MODIFY COLUMN weight_per_unit DECIMAL(38,6) NOT NULL;

ALTER TABLE bins
    MODIFY COLUMN current_weight_occupancy DECIMAL(38,6) NOT NULL,
    MODIFY COLUMN max_weight_capacity DECIMAL(38,6) NOT NULL,
    MODIFY COLUMN min_weight_threshold DECIMAL(38,6) NOT NULL;

ALTER TABLE inventory_stocks
    MODIFY COLUMN quantity DECIMAL(38,6) NOT NULL;

ALTER TABLE inventory_ledger
    MODIFY COLUMN quantity_change DECIMAL(38,6) NULL,
    MODIFY COLUMN balance_before DECIMAL(38,6) NULL,
    MODIFY COLUMN balance_after DECIMAL(38,6) NULL;
