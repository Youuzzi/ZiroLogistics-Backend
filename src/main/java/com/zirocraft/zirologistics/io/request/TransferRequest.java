package com.zirocraft.zirologistics.io.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class TransferRequest {
    @NotBlank(message = "Request ID wajib ada")
    private String requestId;

    @NotBlank(message = "SKU wajib diisi")
    private String sku;

    @NotBlank(message = "Asal Rak (Source Bin) wajib diisi")
    private String sourceBinCode;

    @NotBlank(message = "Tujuan Rak (Destination Bin) wajib diisi")
    private String destinationBinCode;

    @NotNull(message = "Jumlah pemindahan wajib diisi")
    @Positive
    private BigDecimal quantity;

    private String note;
}