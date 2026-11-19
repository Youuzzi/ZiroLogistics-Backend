package com.zirocraft.zirologistics.wms.inventory.http;

import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;

import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryMasterDataService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;

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
public class WmsInventoryMasterDataController {
    private final WmsInventoryMasterDataService service;

    public WmsInventoryMasterDataController(WmsInventoryMasterDataService service) {
        this.service = service;
    }

    @GetMapping("/uoms")
    public List<Map<String, Object>> listUoms(
            Authentication authentication, @RequestParam(required = false) String dimension) {
        return service.listUoms(principal(authentication), dimension);
    }

    @GetMapping("/item-categories")
    public List<Map<String, Object>> listCategories(Authentication authentication) {
        return service.listCategories(principal(authentication));
    }

    @PostMapping("/item-categories")
    public Map<String, Object> createCategory(
            Authentication authentication, @Valid @RequestBody CategoryRequest request) {
        return service.createCategory(principal(authentication), request.toCommand());
    }

    @PutMapping("/item-categories/{categoryPublicId}")
    public Map<String, Object> updateCategory(
            Authentication authentication, @PathVariable String categoryPublicId,
            @Valid @RequestBody CategoryUpdateRequest request) {
        return service.updateCategory(principal(authentication), categoryPublicId,
                request.category().toCommand(), request.expectedVersion());
    }

    @PostMapping("/item-categories/{categoryPublicId}/status")
    public Map<String, Object> changeCategoryStatus(
            Authentication authentication, @PathVariable String categoryPublicId,
            @Valid @RequestBody StatusRequest request) {
        return service.changeCategoryStatus(principal(authentication), categoryPublicId,
                request.status(), request.expectedVersion());
    }

    @GetMapping("/items")
    public List<Map<String, Object>> listItems(Authentication authentication) {
        return service.listItems(principal(authentication));
    }

    @PostMapping("/items")
    public Map<String, Object> createItem(
            Authentication authentication, @Valid @RequestBody ItemRequest request) {
        return service.createItem(principal(authentication), request.toCommand());
    }

    @GetMapping("/items/{itemPublicId}")
    public Map<String, Object> getItem(
            Authentication authentication, @PathVariable String itemPublicId) {
        return service.getItem(principal(authentication), itemPublicId);
    }

    @PutMapping("/items/{itemPublicId}")
    public Map<String, Object> updateItem(
            Authentication authentication, @PathVariable String itemPublicId,
            @Valid @RequestBody ItemUpdateRequest request) {
        return service.updateItem(principal(authentication), itemPublicId,
                request.item().toCommand(), request.expectedVersion());
    }

    @PostMapping("/items/{itemPublicId}/status")
    public Map<String, Object> changeItemStatus(
            Authentication authentication, @PathVariable String itemPublicId,
            @Valid @RequestBody StatusRequest request) {
        return service.changeItemStatus(principal(authentication), itemPublicId,
                request.status(), request.expectedVersion());
    }


    @GetMapping("/items/lookup")
    public Map<String, Object> lookupItem(
            Authentication authentication, @RequestParam String identifierType,
            @RequestParam String value) {
        return service.lookupItemByIdentifier(principal(authentication), identifierType, value);
    }

    @GetMapping("/items/{itemPublicId}/identifiers")
    public List<Map<String, Object>> listIdentifiers(
            Authentication authentication, @PathVariable String itemPublicId) {
        return service.listIdentifiers(principal(authentication), itemPublicId);
    }

    @PostMapping("/items/{itemPublicId}/identifiers")
    public Map<String, Object> addIdentifier(
            Authentication authentication, @PathVariable String itemPublicId,
            @Valid @RequestBody IdentifierRequest request) {
        return service.addIdentifier(principal(authentication), itemPublicId, request.toCommand());
    }

    @PutMapping("/items/{itemPublicId}/identifiers/{identifierPublicId}")
    public Map<String, Object> updateIdentifier(
            Authentication authentication, @PathVariable String itemPublicId,
            @PathVariable String identifierPublicId, @Valid @RequestBody IdentifierRequest request) {
        return service.updateIdentifier(principal(authentication), itemPublicId,
                identifierPublicId, request.toCommand());
    }

    @DeleteMapping("/items/{itemPublicId}/identifiers/{identifierPublicId}")
    public void removeIdentifier(
            Authentication authentication, @PathVariable String itemPublicId,
            @PathVariable String identifierPublicId) {
        service.removeIdentifier(principal(authentication), itemPublicId, identifierPublicId);
    }

    @GetMapping("/items/{itemPublicId}/uom-conversions")
    public List<Map<String, Object>> listUomConversions(
            Authentication authentication, @PathVariable String itemPublicId) {
        return service.listUomConversions(principal(authentication), itemPublicId);
    }

    @PostMapping("/items/{itemPublicId}/uom-conversions")
    public Map<String, Object> addUomConversion(
            Authentication authentication, @PathVariable String itemPublicId,
            @Valid @RequestBody UomConversionRequest request) {
        return service.addUomConversion(principal(authentication), itemPublicId, request.toCommand());
    }

    @PostMapping("/items/{itemPublicId}/uom-conversions/deactivate")
    public Map<String, Object> deactivateUomConversion(
            Authentication authentication, @PathVariable String itemPublicId,
            @Valid @RequestBody UomConversionDeactivateRequest request) {
        return service.deactivateUomConversion(principal(authentication), itemPublicId,
                request.fromUomCode(), request.toUomCode());
    }


    @GetMapping("/items/{itemPublicId}/lots")
    public List<Map<String, Object>> listLots(
            Authentication authentication, @PathVariable String itemPublicId,
            @RequestParam(required = false) String status) {
        return service.listLots(principal(authentication), itemPublicId, status);
    }

    @PostMapping("/items/{itemPublicId}/lots")
    public Map<String, Object> createLot(
            Authentication authentication, @PathVariable String itemPublicId,
            @Valid @RequestBody LotRequest request) {
        return service.createLot(principal(authentication), itemPublicId, request.toCommand());
    }

    @PostMapping("/items/{itemPublicId}/lots/{lotPublicId}/status")
    public Map<String, Object> changeLotStatus(
            Authentication authentication, @PathVariable String itemPublicId,
            @PathVariable String lotPublicId, @Valid @RequestBody VersionedStatusRequest request) {
        return service.changeLotStatus(principal(authentication), itemPublicId, lotPublicId,
                request.status(), request.expectedVersion());
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object candidate = authentication == null ? null : authentication.getPrincipal();
        if (!(candidate instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record CategoryRequest(
            @NotBlank @Size(min = 2, max = 80) String code,
            @NotBlank @Size(min = 2, max = 150) String name,
            @Size(max = 500) String description,
            @Size(max = 36) String parentCategoryPublicId) {
        WmsInventoryMasterDataService.CategoryCommand toCommand() {
            return new WmsInventoryMasterDataService.CategoryCommand(
                    code, name, description, parentCategoryPublicId);
        }
    }

    public record CategoryUpdateRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Valid CategoryRequest category) {}

    public record ItemRequest(
            @NotBlank @Size(min = 2, max = 100) String sku,
            @NotBlank @Size(min = 2, max = 200) String name,
            @Size(max = 4000) String description,
            @Size(max = 36) String categoryPublicId,
            @NotBlank @Size(max = 20) String baseUomCode,
            @NotBlank @Size(max = 30) String trackingMethod,
            @NotNull Boolean stockItem,
            @NotNull Boolean purchasable,
            @NotNull Boolean sellable,
            @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal minimumStock) {
        WmsInventoryMasterDataService.ItemCommand toCommand() {
            return new WmsInventoryMasterDataService.ItemCommand(
                    sku, name, description, categoryPublicId, baseUomCode, trackingMethod,
                    stockItem, purchasable, sellable, minimumStock);
        }
    }

    public record ItemUpdateRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Valid ItemRequest item) {}



    public record LotRequest(
            @NotBlank @Size(max = 120) String lotNumber,
            @Size(max = 120) String supplierLotNumber,
            java.time.LocalDate manufactureDate,
            java.time.LocalDate receivedDate,
            java.time.LocalDate expiryDate,
            @Size(max = 1000) String notes) {
        WmsInventoryMasterDataService.LotCommand toCommand() {
            return new WmsInventoryMasterDataService.LotCommand(
                    lotNumber, supplierLotNumber, manufactureDate, receivedDate, expiryDate, notes);
        }
    }

    public record VersionedStatusRequest(
            @NotBlank @Size(max = 30) String status,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record IdentifierRequest(
            @NotBlank @Size(max = 30) String type,
            @NotBlank @Size(max = 150) String value,
            @NotNull Boolean primary) {
        WmsInventoryMasterDataService.IdentifierCommand toCommand() {
            return new WmsInventoryMasterDataService.IdentifierCommand(type, value, primary);
        }
    }

    public record UomConversionRequest(
            @NotBlank @Size(max = 20) String fromUomCode,
            @NotBlank @Size(max = 20) String toUomCode,
            @NotNull @DecimalMin(value = "0.00000001") @Digits(integer = 11, fraction = 8) BigDecimal factor) {
        WmsInventoryMasterDataService.UomConversionCommand toCommand() {
            return new WmsInventoryMasterDataService.UomConversionCommand(fromUomCode, toUomCode, factor);
        }
    }

    public record UomConversionDeactivateRequest(
            @NotBlank @Size(max = 20) String fromUomCode,
            @NotBlank @Size(max = 20) String toUomCode) {}

    public record StatusRequest(
            @NotBlank @Size(max = 30) String status,
            @NotNull @PositiveOrZero Long expectedVersion) {}
}
