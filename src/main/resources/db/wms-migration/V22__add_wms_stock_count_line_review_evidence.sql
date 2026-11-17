-- Per-line stock-count review evidence. V1-V21 remain immutable.
ALTER TABLE stock_count_lines
  ADD COLUMN reviewed_by_membership_id BIGINT NULL AFTER line_status,
  ADD COLUMN reviewed_at DATETIME(6) NULL AFTER reviewed_by_membership_id,
  ADD KEY fk_stock_count_lines_reviewed_by (reviewed_by_membership_id, tenant_id),
  ADD CONSTRAINT fk_stock_count_lines_reviewed_by
    FOREIGN KEY (reviewed_by_membership_id, tenant_id)
    REFERENCES tenant_memberships (id, tenant_id) ON DELETE RESTRICT;
