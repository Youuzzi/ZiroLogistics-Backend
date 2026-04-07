package com.zirocraft.zirologistics.io.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data @Builder
public class StockResponse {
    private String itemSku;
    private String itemName;
    private String warehouseName;
    private String binCode;
    private BigDecimal quantity;
    private String status;
}