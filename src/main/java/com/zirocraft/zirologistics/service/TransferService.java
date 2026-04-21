package com.zirocraft.zirologistics.service;

import com.zirocraft.zirologistics.io.request.TransferRequest;

public interface TransferService {
    void processTransfer(TransferRequest request, String executorEmail);
}