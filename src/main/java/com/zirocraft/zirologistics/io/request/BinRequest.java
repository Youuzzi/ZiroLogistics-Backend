package com.zirocraft.zirologistics.io.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BinRequest {
    @NotBlank(message = "Warehouse ID wajib diisi")
    private String warehousePublicId; // Kita pakai UUID Warehouse-nya

    private String zoneName; // Misal: Area Basah, Area Kering
    private String rackNumber; // Misal: R01, R02

    @NotBlank(message = "Kode Bin wajib diisi")
    private String binCode; // Misal: A-01-01
}