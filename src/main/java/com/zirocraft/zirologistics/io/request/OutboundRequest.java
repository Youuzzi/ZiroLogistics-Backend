package com.zirocraft.zirologistics.io.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class OutboundRequest {
    @NotBlank(message = "Request ID wajib ada (Idempotency)")
    private String requestId;

    @NotBlank(message = "SKU barang wajib diisi")
    private String sku;

    @NotBlank(message = "Kode Bin/Rak wajib diisi")
    private String binCode;

    @NotNull(message = "Jumlah pengambilan wajib diisi")
    @Positive(message = "Jumlah harus lebih dari 0")
    private BigDecimal quantity;

    private String referenceNo; // No. Surat Jalan / SO
    private String note;
}