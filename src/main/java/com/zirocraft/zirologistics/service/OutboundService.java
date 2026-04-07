package com.zirocraft.zirologistics.service;

import com.zirocraft.zirologistics.io.request.OutboundRequest;

public interface OutboundService {
    void processOutbound(OutboundRequest request, String executorEmail);
}