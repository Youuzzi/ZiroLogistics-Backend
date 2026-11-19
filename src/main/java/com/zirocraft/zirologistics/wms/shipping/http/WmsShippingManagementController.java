package com.zirocraft.zirologistics.wms.shipping.http;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;
import com.zirocraft.zirologistics.wms.shipping.application.WmsShippingManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current/shipping")
@Conditional(WmsHttpApiEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsShippingManagementController {
    private final WmsShippingManagementService service;

    public WmsShippingManagementController(WmsShippingManagementService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication authentication) {
        return service.list(principal(authentication));
    }

    @GetMapping("/{shipmentPublicId}")
    public Map<String, Object> get(Authentication authentication,
                                   @PathVariable String shipmentPublicId) {
        return service.get(principal(authentication), shipmentPublicId);
    }

    @PostMapping("/{shipmentPublicId}/ship")
    public Map<String, Object> ship(Authentication authentication,
                                    @PathVariable String shipmentPublicId,
                                    @Valid @RequestBody ShipRequest request) {
        return service.ship(principal(authentication), shipmentPublicId,
                new WmsShippingManagementService.ShipCommand(
                        request.requestId(), request.carrierPartnerPublicId(),
                        request.trackingNumber(), request.notes()));
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object value = authentication == null ? null : authentication.getPrincipal();
        if (!(value instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record ShipRequest(@NotNull UUID requestId,
                              @Size(max = 36) String carrierPartnerPublicId,
                              @Size(max = 120) String trackingNumber,
                              @Size(max = 1000) String notes) {}
}
