package com.zirocraft.zirologistics.wms.outbound.http;

import com.zirocraft.zirologistics.wms.outbound.application.WmsOutboundShipmentService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current/outbound-shipments")
@Conditional(WmsHttpApiEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsOutboundShipmentController {
    private final WmsOutboundShipmentService service;

    public WmsOutboundShipmentController(WmsOutboundShipmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication authentication) {
        return service.list(principal(authentication));
    }

    @GetMapping("/{shipmentPublicId}")
    public Map<String, Object> get(Authentication authentication, @PathVariable String shipmentPublicId) {
        return service.get(principal(authentication), shipmentPublicId);
    }

    @PostMapping
    public Map<String, Object> create(Authentication authentication,
                                      @Valid @RequestBody CreateShipmentRequest request) {
        return service.create(principal(authentication), request.toCommand());
    }

    @PostMapping("/{shipmentPublicId}/lines")
    public Map<String, Object> addLine(Authentication authentication,
                                       @PathVariable String shipmentPublicId,
                                       @Valid @RequestBody ShipmentLineRequest request) {
        return service.addLine(principal(authentication), shipmentPublicId, request.toCommand());
    }

    @PostMapping("/{shipmentPublicId}/lines/{linePublicId}/allocations")
    public Map<String, Object> allocate(Authentication authentication,
                                        @PathVariable String shipmentPublicId,
                                        @PathVariable String linePublicId,
                                        @Valid @RequestBody AllocationRequest request) {
        return service.allocate(principal(authentication), shipmentPublicId, linePublicId, request.toCommand());
    }

    @PostMapping("/{shipmentPublicId}/release")
    public Map<String, Object> release(Authentication authentication, @PathVariable String shipmentPublicId) {
        return service.release(principal(authentication), shipmentPublicId);
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object candidate = authentication == null ? null : authentication.getPrincipal();
        if (!(candidate instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record CreateShipmentRequest(
            @NotBlank @Size(max = 36) String warehousePublicId,
            @NotBlank @Size(max = 36) String customerPublicId,
            @Size(max = 36) String customerAddressPublicId,
            @NotBlank @Size(max = 60) String documentNumber,
            @Size(max = 100) String externalReference,
            @Size(max = 30) String shipmentType,
            LocalDateTime scheduledAt,
            @Size(max = 1000) String notes) {
        WmsOutboundShipmentService.CreateShipmentCommand toCommand() {
            return new WmsOutboundShipmentService.CreateShipmentCommand(
                    warehousePublicId, customerPublicId, customerAddressPublicId, documentNumber,
                    externalReference, shipmentType, scheduledAt, notes);
        }
    }

    public record ShipmentLineRequest(
            @NotBlank @Size(max = 36) String itemPublicId,
            @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal requestedQty,
            @Size(max = 1000) String notes) {
        WmsOutboundShipmentService.ShipmentLineCommand toCommand() {
            return new WmsOutboundShipmentService.ShipmentLineCommand(itemPublicId, requestedQty, notes);
        }
    }

    public record AllocationRequest(
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid AllocationLineRequest> allocations) {
        WmsOutboundShipmentService.AllocationCommand toCommand() {
            return new WmsOutboundShipmentService.AllocationCommand(
                    allocations.stream().map(AllocationLineRequest::toCommand).toList());
        }
    }

    public record AllocationLineRequest(
            @NotBlank @Size(max = 36) String locationPublicId,
            @Size(max = 36) String lotPublicId,
            @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal quantity,
            @Size(max = 100) List<@NotBlank @Size(max = 36) String> serialPublicIds) {
        WmsOutboundShipmentService.AllocationLine toCommand() {
            return new WmsOutboundShipmentService.AllocationLine(
                    locationPublicId, lotPublicId, quantity, serialPublicIds);
        }
    }
}
