package com.zirocraft.zirologistics.wms.stockcount.http;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsIdentityHttpEnabledCondition;
import com.zirocraft.zirologistics.wms.stockcount.application.WmsStockCountManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
@RequestMapping("/api/wms/v1/tenants/current/stock-counts")
@Conditional(WmsIdentityHttpEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsStockCountManagementController {
    private final WmsStockCountManagementService service;

    public WmsStockCountManagementController(WmsStockCountManagementService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication authentication) {
        return service.list(principal(authentication));
    }

    @GetMapping("/{countPublicId}")
    public Map<String, Object> get(Authentication authentication, @PathVariable String countPublicId) {
        return service.get(principal(authentication), countPublicId);
    }

    @PostMapping
    public Map<String, Object> create(Authentication authentication, @Valid @RequestBody CreateRequest request) {
        return service.create(principal(authentication), new WmsStockCountManagementService.CreateCountCommand(
                request.warehousePublicId(), request.documentNumber(), request.countType(), request.countMode(),
                request.locationPublicIds(), request.notes()));
    }

    @PostMapping("/{countPublicId}/plan")
    public Map<String, Object> plan(Authentication authentication, @PathVariable String countPublicId) {
        return service.plan(principal(authentication), countPublicId);
    }

    @PostMapping("/{countPublicId}/start")
    public Map<String, Object> start(Authentication authentication, @PathVariable String countPublicId) {
        return service.start(principal(authentication), countPublicId);
    }

    @PostMapping("/{countPublicId}/cancel")
    public Map<String, Object> cancel(Authentication authentication, @PathVariable String countPublicId) {
        return service.cancel(principal(authentication), countPublicId);
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object value = authentication == null ? null : authentication.getPrincipal();
        if (!(value instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record CreateRequest(
            @NotBlank @Size(max = 36) String warehousePublicId,
            @NotBlank @Size(max = 60) String documentNumber,
            @NotBlank @Size(max = 30) String countType,
            @NotBlank @Size(max = 20) String countMode,
            @NotEmpty @Size(min = 1, max = 100) List<@NotBlank @Size(max = 36) String> locationPublicIds,
            @Size(max = 1000) String notes) {}
}
