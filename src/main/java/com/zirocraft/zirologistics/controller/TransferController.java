package com.zirocraft.zirologistics.controller;

import com.zirocraft.zirologistics.io.request.TransferRequest;
import com.zirocraft.zirologistics.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1.0/transfer")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<String> processTransfer(@Valid @RequestBody TransferRequest request, Authentication authentication) {
        String executorEmail = authentication.getName();
        transferService.processTransfer(request, executorEmail);
        return ResponseEntity.ok("Internal Transfer Processed Successfully!");
    }
}