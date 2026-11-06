package com.zirocraft.zirologistics.io.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class LowStockAlertResponse {
    private String itemSku;
    private String itemName;
    private String warehouseName;
    private BigDecimal availableQuantity;
    private BigDecimal minStockLevel;
}
