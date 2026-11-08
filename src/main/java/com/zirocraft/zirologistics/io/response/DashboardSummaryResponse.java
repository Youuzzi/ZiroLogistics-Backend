package com.zirocraft.zirologistics.io.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DashboardSummaryResponse {
    private long totalWarehouses;
    private long totalBins;
    private long totalItems;

    // Null until a reliable transaction-counting rule is implemented.
    private Long totalTransactionsToday;
}