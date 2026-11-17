package com.zirocraft.zirologistics.wms.adjustment.http;

import com.zirocraft.zirologistics.wms.adjustment.application.WmsInventoryAdjustmentService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsIdentityHttpEnabledCondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current")
@Conditional(WmsIdentityHttpEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsInventoryAdjustmentController {
    private final WmsInventoryAdjustmentService service;

    public WmsInventoryAdjustmentController(WmsInventoryAdjustmentService service) {
        this.service = service;
    }

    @GetMapping("/inventory-adjustments")
    public List<Map<String, Object>> list(Authentication authentication) {
        return service.list(principal(authentication));
    }

    @GetMapping("/inventory-adjustments/{adjustmentPublicId}")
    public Map<String, Object> get(Authentication authentication, @PathVariable String adjustmentPublicId) {
        return service.get(principal(authentication), adjustmentPublicId);
    }

    @PostMapping("/stock-counts/{countPublicId}/inventory-adjustments")
    public Map<String, Object> createFromCount(Authentication authentication,
                                               @PathVariable String countPublicId,
                                               @Valid @RequestBody CreateFromCountRequest request) {
        return service.createFromApprovedCount(principal(authentication), countPublicId,
                new WmsInventoryAdjustmentService.CreateFromCountCommand(
                        request.documentNumber(), request.notes()));
    }

    @PostMapping("/inventory-adjustments/{adjustmentPublicId}/submit")
    public Map<String, Object> submit(Authentication authentication, @PathVariable String adjustmentPublicId) {
        return service.submit(principal(authentication), adjustmentPublicId);
    }

    @PostMapping("/inventory-adjustments/{adjustmentPublicId}/approve")
    public Map<String, Object> approve(Authentication authentication,
                                       @PathVariable String adjustmentPublicId,
                                       @Valid @RequestBody(required = false) ApprovalRequest request) {
        return service.approve(principal(authentication), adjustmentPublicId,
                request == null ? null : request.approvalNotes());
    }

    @PostMapping("/inventory-adjustments/{adjustmentPublicId}/reject")
    public Map<String, Object> reject(Authentication authentication,
                                      @PathVariable String adjustmentPublicId,
                                      @Valid @RequestBody RejectRequest request) {
        return service.reject(principal(authentication), adjustmentPublicId, request.reason());
    }

    @PostMapping("/inventory-adjustments/{adjustmentPublicId}/post")
    public Map<String, Object> post(Authentication authentication, @PathVariable String adjustmentPublicId) {
        return service.post(principal(authentication), adjustmentPublicId);
    }

    public record CreateFromCountRequest(
            @NotBlank @Size(max = 60) String documentNumber,
            @Size(max = 1000) String notes) {}

    public record ApprovalRequest(@Size(max = 1000) String approvalNotes) {}

    public record RejectRequest(@NotBlank @Size(max = 1000) String reason) {}

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object value = authentication == null ? null : authentication.getPrincipal();
        if (!(value instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }
}