package com.zirocraft.zirologistics.io.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data @Builder
public class ItemResponse {
    private String publicId;
    private String sku;
    private String name;
    private String description;
    private String baseUom;
    private BigDecimal minStockLevel;
    private BigDecimal weightPerUnit; // Biar UI bisa nampilin beratnya
}