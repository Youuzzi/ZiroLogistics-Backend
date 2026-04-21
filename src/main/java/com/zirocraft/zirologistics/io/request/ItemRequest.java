package com.zirocraft.zirologistics.io.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class ItemRequest {
    @NotBlank(message = "SKU wajib diisi")
    private String sku;

    @NotBlank(message = "Nama barang wajib diisi")
    private String name;

    private String description;

    @NotBlank(message = "Satuan (UoM) wajib diisi")
    private String baseUom;

    @NotNull(message = "Minimal stok level wajib diisi")
    private BigDecimal minStockLevel;

    @NotNull(message = "Berat per unit wajib diisi")
    @Positive(message = "Berat harus lebih dari 0")
    private BigDecimal weightPerUnit; // Industrial Requirement
}