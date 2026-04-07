package com.zirocraft.zirologistics.io.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Builder
public class LedgerResponse {
    private String publicId;
    private String itemSku;
    private String itemName;
    private String binCode;
    private String transactionType; // INBOUND, OUTBOUND, etc
    private BigDecimal quantityChange;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String referenceNo;
    private String userId;
    private String note;
    private LocalDateTime createdAt;
}