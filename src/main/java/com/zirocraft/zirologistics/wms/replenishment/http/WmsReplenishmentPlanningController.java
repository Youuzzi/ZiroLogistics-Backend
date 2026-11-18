package com.zirocraft.zirologistics.wms.replenishment.http;

import com.zirocraft.zirologistics.wms.replenishment.application.WmsReplenishmentPlanningService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsIdentityHttpEnabledCondition;
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
import java.util.List;
import java.util.Map;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current/replenishment-rules")
@Conditional(WmsIdentityHttpEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsReplenishmentPlanningController {
    private final WmsReplenishmentPlanningService service;

    public WmsReplenishmentPlanningController(WmsReplenishmentPlanningService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication authentication) {
        return service.list(principal(authentication));
    }

    @GetMapping("/{rulePublicId}")
    public Map<String, Object> get(Authentication authentication, @PathVariable String rulePublicId) {
        return service.get(principal(authentication), rulePublicId);
    }

    @PostMapping
    public Map<String, Object> create(Authentication authentication, @Valid @RequestBody RuleRequest request) {
        return service.create(principal(authentication), command(request));
    }

    @PutMapping("/{rulePublicId}")
    public Map<String, Object> update(Authentication authentication, @PathVariable String rulePublicId,
                                      @Valid @RequestBody UpdateRuleRequest request) {
        return service.update(principal(authentication), rulePublicId, request.version(), command(request.rule()));
    }

    @PostMapping("/{rulePublicId}/deactivate")
    public Map<String, Object> deactivate(Authentication authentication, @PathVariable String rulePublicId,
                                          @Valid @RequestBody VersionRequest request) {
        return service.deactivate(principal(authentication), rulePublicId, request.version());
    }

    @PostMapping("/{rulePublicId}/plan")
    public Map<String, Object> plan(Authentication authentication, @PathVariable String rulePublicId,
                                    @Valid @RequestBody(required = false) PlanRequest request) {
        return service.plan(principal(authentication), rulePublicId,
                request == null ? null : new WmsReplenishmentPlanningService.PlanCommand(
                        request.requestedQty(), request.notes()));
    }

    private static WmsReplenishmentPlanningService.RuleCommand command(RuleRequest request) {
        return new WmsReplenishmentPlanningService.RuleCommand(
                request.warehousePublicId(), request.itemPublicId(), request.sourceLocationPublicId(),
                request.destinationLocationPublicId(), request.ruleName(), request.ruleType(),
                request.minimumQty(), request.replenishToQty(), request.maximumReplenishmentQty(),
                request.priority(), request.notes());
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object value = authentication == null ? null : authentication.getPrincipal();
        if (!(value instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record RuleRequest(
            @NotBlank @Size(max = 36) String warehousePublicId,
            @NotBlank @Size(max = 36) String itemPublicId,
            @NotBlank @Size(max = 36) String sourceLocationPublicId,
            @NotBlank @Size(max = 36) String destinationLocationPublicId,
            @NotBlank @Size(max = 150) String ruleName,
            @NotBlank @Size(max = 30) String ruleType,
            @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal minimumQty,
            @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal replenishToQty,
            @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal maximumReplenishmentQty,
            @Size(max = 20) String priority,
            @Size(max = 1000) String notes) {}

    public record UpdateRuleRequest(@NotNull Long version, @NotNull @Valid RuleRequest rule) {}
    public record VersionRequest(@NotNull Long version) {}
    public record PlanRequest(
            @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal requestedQty,
            @Size(max = 1000) String notes) {}
}
