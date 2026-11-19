package com.zirocraft.zirologistics.wms.inventory.http;

import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;

import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryQueryService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current/inventory")
@Conditional(WmsHttpApiEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsInventoryController {
    private final WmsInventoryPostingService postingService;
    private final WmsInventoryQueryService queryService;

    public WmsInventoryController(WmsInventoryPostingService postingService, WmsInventoryQueryService queryService) {
        this.postingService = postingService;
        this.queryService = queryService;
    }

    @PostMapping("/postings")
    public Map<String, Object> post(Authentication authentication, @Valid @RequestBody PostingRequest request) {
        List<WmsInventoryPostingService.PostingLeg> legs = request.legs().stream()
                .map(PostingLegRequest::toCommand).toList();
        return postingService.post(principal(authentication), new WmsInventoryPostingService.PostingCommand(
                request.requestId(), request.movementType(), legs, request.notes()));
    }

    @GetMapping("/balances")
    public Map<String, Object> balances(Authentication authentication,
            @RequestParam(required = false) String itemPublicId,
            @RequestParam(required = false) String warehousePublicId,
            @RequestParam(required = false) String locationPublicId,
            @RequestParam(required = false) String stockStatus,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return queryService.balances(principal(authentication), itemPublicId, warehousePublicId,
                locationPublicId, stockStatus, page, size);
    }

    @GetMapping("/ledger")
    public Map<String, Object> ledger(Authentication authentication,
            @RequestParam(required = false) String itemPublicId,
            @RequestParam(required = false) String warehousePublicId,
            @RequestParam(required = false) String locationPublicId,
            @RequestParam(required = false) String movementType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return queryService.ledger(principal(authentication), itemPublicId, warehousePublicId,
                locationPublicId, movementType, page, size);
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object candidate = authentication == null ? null : authentication.getPrincipal();
        if (!(candidate instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record PostingRequest(@NotNull UUID requestId, @NotBlank @Size(max = 40) String movementType,
                                 @NotNull @Size(min = 1, max = 200) List<@NotNull @Valid PostingLegRequest> legs,
                                 @Size(max = 1000) String notes) {}

    public record PostingLegRequest(@NotBlank @Size(max = 36) String warehousePublicId,
                                    @NotBlank @Size(max = 36) String locationPublicId,
                                    @NotBlank @Size(max = 36) String itemPublicId,
                                    @Size(max = 36) String lotPublicId,
                                    @Size(max = 36) String serialPublicId,
                                    @Size(max = 150) String serialNumber,
                                    @Size(max = 30) String stockStatus,
                                    @NotNull @DecimalMin(value = "0.0001") BigDecimal quantity) {
        WmsInventoryPostingService.PostingLeg toCommand() {
            return new WmsInventoryPostingService.PostingLeg(warehousePublicId, locationPublicId, itemPublicId,
                    lotPublicId, serialPublicId, serialNumber, stockStatus, quantity);
        }
    }
}