-- Serialized identities observed during a stock-count result round.
-- V1-V20 remain immutable; one row maps a serial to one submitted result round.

ALTER TABLE stock_count_results
  ADD UNIQUE KEY uk_stock_count_results_id_scope
    (id, tenant_id, stock_count_line_id, item_id, warehouse_id);

CREATE TABLE stock_count_result_serials (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  stock_count_result_id BIGINT NOT NULL,
  stock_count_line_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  serial_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_count_result_serials_result_serial
    (tenant_id, stock_count_result_id, serial_id),
  KEY idx_stock_count_result_serials_line
    (tenant_id, stock_count_line_id, stock_count_result_id),
  KEY idx_stock_count_result_serials_serial
    (tenant_id, serial_id, stock_count_result_id),
  CONSTRAINT fk_stock_count_result_serials_result
    FOREIGN KEY (stock_count_result_id, tenant_id, stock_count_line_id, item_id, warehouse_id)
    REFERENCES stock_count_results (id, tenant_id, stock_count_line_id, item_id, warehouse_id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_stock_count_result_serials_serial
    FOREIGN KEY (serial_id, tenant_id, item_id)
    REFERENCES inventory_serials (id, tenant_id, item_id)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
