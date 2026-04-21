package com.zirocraft.zirologistics.controller;

import com.zirocraft.zirologistics.io.response.DashboardSummaryResponse;
import com.zirocraft.zirologistics.service.impl.DashboardServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1.0/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardServiceImpl dashboardService;

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryResponse> getSummary() {
        return ResponseEntity.ok(dashboardService.getSummary());
    }
}