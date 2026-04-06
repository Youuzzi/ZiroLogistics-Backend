package com.zirocraft.zirologistics.controller;

import com.zirocraft.zirologistics.io.request.InboundRequest;
import com.zirocraft.zirologistics.service.InboundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1.0/inbound")
@RequiredArgsConstructor
public class InboundController {

    private final InboundService inboundService;

    @PostMapping
    public ResponseEntity<String> processInbound(@Valid @RequestBody InboundRequest request, Authentication authentication) {
        // Ambil email dari user yang sedang login (Audit Trail)
        String executorEmail = authentication.getName();

        inboundService.processInbound(request, executorEmail);

        return ResponseEntity.ok("Barang berhasil masuk ke gudang (Inbound Success)!");
    }
}