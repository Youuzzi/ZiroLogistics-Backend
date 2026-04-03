package com.zirocraft.zirologistics.io.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WarehouseRequest {

    @NotBlank(message = "Kode gudang wajib diisi")
    @Size(min = 3, max = 50, message = "Kode gudang minimal 3 karakter")
    private String code;

    @NotBlank(message = "Nama gudang wajib diisi")
    private String name;

    private String address;
}