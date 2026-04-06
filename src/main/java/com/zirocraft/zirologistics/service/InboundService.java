package com.zirocraft.zirologistics.service;

import com.zirocraft.zirologistics.io.request.InboundRequest;

public interface InboundService {
    void processInbound(InboundRequest request, String executorEmail);
}