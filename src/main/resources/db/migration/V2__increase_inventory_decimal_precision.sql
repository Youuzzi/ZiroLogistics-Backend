-- Preserve the precision required by the quantity and physical-weight domains.
-- Quantities/minimum stock: up to 3 fractional digits (KG).
-- Weight per base unit: up to 6 fractional digits (KG per Base UOM).
-- Occupancy/capacity: 9 fractional digits because 6-digit unit weight x 3-digit quantity.
ALTER TABLE items
    MODIFY COLUMN min_stock_level DECIMAL(38,3) NOT NULL,
    MODIFY COLUMN weight_per_unit DECIMAL(38,6) NOT NULL;

ALTER TABLE bins
    MODIFY COLUMN current_weight_occupancy DECIMAL(38,9) NOT NULL,
    MODIFY COLUMN max_weight_capacity DECIMAL(38,9) NOT NULL,
    MODIFY COLUMN min_weight_threshold DECIMAL(38,9) NOT NULL;

ALTER TABLE inventory_stocks
    MODIFY COLUMN quantity DECIMAL(38,3) NOT NULL;

ALTER TABLE inventory_ledger
    MODIFY COLUMN quantity_change DECIMAL(38,3) NULL,
    MODIFY COLUMN balance_before DECIMAL(38,3) NULL,
    MODIFY COLUMN balance_after DECIMAL(38,3) NULL;
