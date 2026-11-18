package com.zirocraft.zirologistics.wms.replenishment.http;

import com.zirocraft.zirologistics.wms.replenishment.application.WmsReplenishmentTaskService;
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
import java.util.UUID;

@RestController
@Profile("wms")
@Conditional(WmsIdentityHttpEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsReplenishmentTaskController {
    private final WmsReplenishmentTaskService service;

    public WmsReplenishmentTaskController(WmsReplenishmentTaskService service) {
        this.service = service;
    }

    @GetMapping("/api/wms/v1/tenants/current/replenishment-tasks")
    public List<Map<String, Object>> list(Authentication authentication) {
        return service.list(principal(authentication));
    }

    @GetMapping("/api/wms/v1/tenants/current/replenishment-tasks/{taskPublicId}")
    public Map<String, Object> get(Authentication authentication, @PathVariable String taskPublicId) {
        return service.get(principal(authentication), taskPublicId);
    }

    @PostMapping("/api/wms/v1/tenants/current/replenishment-tasks")
    public Map<String, Object> createManual(Authentication authentication,
                                             @Valid @RequestBody ManualTaskRequest request) {
        List<WmsReplenishmentTaskService.ManualTaskLine> lines = request.lines().stream()
                .map(line -> new WmsReplenishmentTaskService.ManualTaskLine(
                        line.itemPublicId(), line.sourceLocationPublicId(), line.destinationLocationPublicId(),
                        line.lotPublicId(), line.quantity(), line.notes()))
                .toList();
        return service.createManual(principal(authentication),
                new WmsReplenishmentTaskService.ManualTaskCommand(
                        request.warehousePublicId(), request.priority(), request.notes(), lines));
    }

    @PostMapping("/api/wms/v1/tenants/current/replenishment-tasks/{taskPublicId}/release")
    public Map<String, Object> release(Authentication authentication, @PathVariable String taskPublicId,
                                       @Valid @RequestBody VersionRequest request) {
        return service.release(principal(authentication), taskPublicId, request.version());
    }

    @PostMapping("/api/wms/v1/tenants/current/replenishment-tasks/{taskPublicId}/assign")
    public Map<String, Object> assign(Authentication authentication, @PathVariable String taskPublicId,
                                      @Valid @RequestBody AssignRequest request) {
        return service.assign(principal(authentication), taskPublicId, request.version(),
                request.assigneeMembershipPublicId());
    }

    @PostMapping("/api/wms/v1/tenants/current/replenishment-tasks/{taskPublicId}/start")
    public Map<String, Object> start(Authentication authentication, @PathVariable String taskPublicId,
                                     @Valid @RequestBody VersionRequest request) {
        return service.start(principal(authentication), taskPublicId, request.version());
    }

    @PostMapping("/api/wms/v1/tenants/current/replenishment-tasks/{taskPublicId}/confirm")
    public Map<String, Object> confirm(Authentication authentication, @PathVariable String taskPublicId,
                                       @Valid @RequestBody ConfirmRequest request) {
        return service.confirm(principal(authentication), taskPublicId,
                new WmsReplenishmentTaskService.ConfirmCommand(request.requestId(), request.lineNumber(),
                        request.quantity(), request.serialPublicIds(), request.notes()));
    }

    @PostMapping("/api/wms/v1/tenants/current/replenishment-tasks/{taskPublicId}/cancel")
    public Map<String, Object> cancel(Authentication authentication, @PathVariable String taskPublicId,
                                      @Valid @RequestBody CancelRequest request) {
        return service.cancel(principal(authentication), taskPublicId, request.version(), request.reason());
    }

    @PostMapping("/api/wms/v1/tenants/current/replenishment-confirmations/{confirmationPublicId}/reverse")
    public Map<String, Object> reverse(Authentication authentication, @PathVariable String confirmationPublicId,
                                       @Valid @RequestBody ReverseRequest request) {
        return service.reverse(principal(authentication), confirmationPublicId,
                new WmsReplenishmentTaskService.ReverseCommand(request.requestId(), request.notes()));
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object value = authentication == null ? null : authentication.getPrincipal();
        if (!(value instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record ManualTaskRequest(@NotBlank @Size(max = 36) String warehousePublicId,
                                    @Size(max = 20) String priority,
                                    @Size(max = 1000) String notes,
                                    @NotNull @Size(min = 1, max = 100) List<@Valid ManualTaskLineRequest> lines) {}
    public record ManualTaskLineRequest(@NotBlank @Size(max = 36) String itemPublicId,
                                        @NotBlank @Size(max = 36) String sourceLocationPublicId,
                                        @NotBlank @Size(max = 36) String destinationLocationPublicId,
                                        @Size(max = 36) String lotPublicId,
                                        @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4)
                                        BigDecimal quantity,
                                        @Size(max = 1000) String notes) {}
    public record VersionRequest(@NotNull Long version) {}
    public record AssignRequest(@NotNull Long version,
                                @NotBlank @Size(max = 36) String assigneeMembershipPublicId) {}
    public record ConfirmRequest(@NotNull UUID requestId, @NotNull Integer lineNumber,
                                 @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4)
                                 BigDecimal quantity,
                                 List<@Size(min = 1, max = 36) String> serialPublicIds,
                                 @Size(max = 1000) String notes) {}
    public record CancelRequest(@NotNull Long version, @NotNull @Size(min = 1, max = 1000) String reason) {}
    public record ReverseRequest(@NotNull UUID requestId, @Size(max = 1000) String notes) {}
}
