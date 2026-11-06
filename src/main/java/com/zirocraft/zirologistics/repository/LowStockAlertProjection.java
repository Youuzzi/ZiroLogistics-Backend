package com.zirocraft.zirologistics.repository;

import java.math.BigDecimal;

/**
 * Aggregated low-stock result for one item in one warehouse.
 */
public interface LowStockAlertProjection {
    String getItemSku();
    String getItemName();
    String getWarehouseName();
    BigDecimal getAvailableQuantity();
    BigDecimal getMinStockLevel();
}
