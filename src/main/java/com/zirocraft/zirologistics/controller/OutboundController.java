package com.zirocraft.zirologistics.controller;

import com.zirocraft.zirologistics.io.request.OutboundRequest;
import com.zirocraft.zirologistics.service.OutboundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1.0/outbound")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundService outboundService;

    @PostMapping
    public ResponseEntity<String> processOutbound(@Valid @RequestBody OutboundRequest request, Authentication authentication) {
        String executorEmail = authentication.getName();
        outboundService.processOutbound(request, executorEmail);
        return ResponseEntity.ok("Barang berhasil dikeluarkan (Outbound Success)!");
    }
}