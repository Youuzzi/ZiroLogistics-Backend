package com.zirocraft.zirologistics.wms.warehouse.http;

import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;
import com.zirocraft.zirologistics.wms.warehouse.application.WmsWarehouseManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current")
@Conditional(WmsHttpApiEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsWarehouseController {
    private final WmsWarehouseManagementService service;

    public WmsWarehouseController(WmsWarehouseManagementService service) {
        this.service = service;
    }

    @GetMapping("/warehouses")
    public List<Map<String, Object>> listWarehouses(Authentication authentication) {
        return service.listWarehouses(principal(authentication));
    }

    @PostMapping("/warehouses")
    public Map<String, Object> createWarehouse(
            Authentication authentication, @Valid @RequestBody WarehouseRequest request) {
        return service.createWarehouse(principal(authentication), request.toCommand());
    }

    @GetMapping("/warehouses/{warehousePublicId}")
    public Map<String, Object> getWarehouse(
            Authentication authentication, @PathVariable String warehousePublicId) {
        return service.getWarehouse(principal(authentication), warehousePublicId);
    }

    @PutMapping("/warehouses/{warehousePublicId}")
    public Map<String, Object> updateWarehouse(
            Authentication authentication, @PathVariable String warehousePublicId,
            @Valid @RequestBody WarehouseUpdateRequest request) {
        return service.updateWarehouse(principal(authentication), warehousePublicId,
                request.warehouse().toCommand(), request.expectedVersion());
    }

    @PostMapping("/warehouses/{warehousePublicId}/status")
    public Map<String, Object> changeWarehouseStatus(
            Authentication authentication, @PathVariable String warehousePublicId,
            @Valid @RequestBody StatusRequest request) {
        return service.changeWarehouseStatus(principal(authentication), warehousePublicId,
                request.status(), request.expectedVersion());
    }

    @GetMapping("/warehouses/{warehousePublicId}/locations")
    public List<Map<String, Object>> listLocations(
            Authentication authentication, @PathVariable String warehousePublicId) {
        return service.listLocations(principal(authentication), warehousePublicId);
    }

    @PostMapping("/warehouses/{warehousePublicId}/locations")
    public Map<String, Object> createLocation(
            Authentication authentication, @PathVariable String warehousePublicId,
            @Valid @RequestBody LocationRequest request) {
        return service.createLocation(principal(authentication), warehousePublicId, request.toCommand());
    }

    @GetMapping("/warehouses/{warehousePublicId}/locations/{locationPublicId}")
    public Map<String, Object> getLocation(
            Authentication authentication, @PathVariable String warehousePublicId,
            @PathVariable String locationPublicId) {
        return service.getLocation(principal(authentication), warehousePublicId, locationPublicId);
    }

    @PutMapping("/warehouses/{warehousePublicId}/locations/{locationPublicId}")
    public Map<String, Object> updateLocation(
            Authentication authentication, @PathVariable String warehousePublicId,
            @PathVariable String locationPublicId, @Valid @RequestBody LocationUpdateRequest request) {
        return service.updateLocation(principal(authentication), warehousePublicId, locationPublicId,
                request.location().toCommand(), request.expectedVersion());
    }

    @PostMapping("/warehouses/{warehousePublicId}/locations/{locationPublicId}/status")
    public Map<String, Object> changeLocationStatus(
            Authentication authentication, @PathVariable String warehousePublicId,
            @PathVariable String locationPublicId, @Valid @RequestBody StatusRequest request) {
        return service.changeLocationStatus(principal(authentication), warehousePublicId, locationPublicId,
                request.status(), request.expectedVersion());
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object candidate = authentication == null ? null : authentication.getPrincipal();
        if (!(candidate instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record WarehouseRequest(
            @NotBlank @Size(min = 2, max = 64) String code,
            @NotBlank @Size(min = 2, max = 150) String name,
            @Size(max = 500) String description,
            @Size(max = 255) String addressLine,
            @Size(max = 100) String city,
            @Size(max = 100) String province,
            @Size(max = 20) String postalCode,
            @NotBlank @Size(min = 2, max = 2) String countryCode,
            @Size(max = 120) String contactName,
            @Size(max = 30) String contactPhone,
            @Size(max = 254) String contactEmail,
            @NotBlank @Size(max = 60) String timezone) {
        WmsWarehouseManagementService.WarehouseCommand toCommand() {
            return new WmsWarehouseManagementService.WarehouseCommand(
                    code, name, description, addressLine, city, province, postalCode,
                    countryCode, contactName, contactPhone, contactEmail, timezone);
        }
    }

    public record WarehouseUpdateRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Valid WarehouseRequest warehouse) {
    }

    public record StatusRequest(
            @NotBlank @Size(max = 30) String status,
            @NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record LocationRequest(
            @NotBlank @Size(min = 2, max = 100) String code,
            @NotBlank @Size(min = 2, max = 150) String name,
            @NotBlank @Size(max = 30) String type,
            @Size(max = 500) String description,
            @Size(max = 36) String parentLocationPublicId,
            @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal maxWeightCapacity,
            @Size(max = 10) String weightUom,
            @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal maxVolumeCapacity,
            @Size(max = 10) String volumeUom,
            @NotNull Boolean pickable,
            @NotNull Boolean receivable,
            @NotNull Boolean allowMixedSkus,
            @NotNull Boolean allowMixedLots,
            @NotNull @PositiveOrZero Integer pickingSequence,
            @NotNull @PositiveOrZero Integer putawaySequence) {
        WmsWarehouseManagementService.LocationCommand toCommand() {
            return new WmsWarehouseManagementService.LocationCommand(
                    code, name, type, description, parentLocationPublicId,
                    maxWeightCapacity, weightUom, maxVolumeCapacity, volumeUom,
                    pickable, receivable, allowMixedSkus, allowMixedLots,
                    pickingSequence, putawaySequence);
        }
    }

    public record LocationUpdateRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Valid LocationRequest location) {
    }
}
