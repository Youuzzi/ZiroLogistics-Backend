package com.zirocraft.zirologistics.io.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data @Builder
public class BinResponse {
    private String publicId;
    private String warehouseName;
    private String zoneName;
    private String rackNumber;
    private String binCode;
    private BigDecimal maxWeightCapacity;
    private BigDecimal minWeightThreshold;
    private BigDecimal currentWeightOccupancy;
}