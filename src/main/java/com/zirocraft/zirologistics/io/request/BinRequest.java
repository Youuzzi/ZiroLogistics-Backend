package com.zirocraft.zirologistics.io.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class BinRequest {
    @NotBlank(message = "Warehouse ID wajib diisi")
    private String warehousePublicId;

    private String zoneName;
    private String rackNumber;

    @NotBlank(message = "Kode Bin wajib diisi")
    private String binCode;

    @NotNull(message = "Kapasitas berat wajib diisi")
    @Positive
    private BigDecimal maxWeightCapacity;

    private BigDecimal minWeightThreshold;
}