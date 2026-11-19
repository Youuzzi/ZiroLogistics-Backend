package com.zirocraft.zirologistics.wms.putaway.http;

import com.zirocraft.zirologistics.wms.putaway.application.WmsPutawayManagementService;
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
import java.util.UUID;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current/putaway-tasks")
@Conditional(WmsHttpApiEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsPutawayManagementController {
    private final WmsPutawayManagementService service;

    public WmsPutawayManagementController(WmsPutawayManagementService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication authentication) {
        return service.list(principal(authentication));
    }

    @GetMapping("/{taskPublicId}")
    public Map<String, Object> get(Authentication authentication, @PathVariable String taskPublicId) {
        return service.get(principal(authentication), taskPublicId);
    }

    @PostMapping
    public Map<String, Object> create(Authentication authentication,
                                       @Valid @RequestBody CreateTaskRequest request) {
        return service.create(principal(authentication), request.toCommand());
    }

    @PostMapping("/{taskPublicId}/ready")
    public Map<String, Object> ready(Authentication authentication, @PathVariable String taskPublicId) {
        return service.ready(principal(authentication), taskPublicId);
    }

    @PostMapping("/{taskPublicId}/assign")
    public Map<String, Object> assign(Authentication authentication, @PathVariable String taskPublicId,
                                      @Valid @RequestBody AssignTaskRequest request) {
        return service.assign(principal(authentication), taskPublicId,
                new WmsPutawayManagementService.AssignTaskCommand(request.membershipPublicId()));
    }

    @PostMapping("/{taskPublicId}/start")
    public Map<String, Object> start(Authentication authentication, @PathVariable String taskPublicId) {
        return service.start(principal(authentication), taskPublicId);
    }

    @PostMapping("/{taskPublicId}/lines/{linePublicId}/confirm")
    public Map<String, Object> confirm(Authentication authentication,
                                       @PathVariable String taskPublicId,
                                       @PathVariable String linePublicId,
                                       @Valid @RequestBody ConfirmPutawayRequest request) {
        return service.confirm(principal(authentication), taskPublicId, linePublicId, request.toCommand());
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object candidate = authentication == null ? null : authentication.getPrincipal();
        if (!(candidate instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record CreateTaskRequest(
            @NotBlank @Size(max = 36) String receiptPublicId,
            @NotBlank @Size(max = 60) String taskNumber,
            @Size(max = 20) String priority,
            LocalDateTime plannedAt,
            @Size(max = 1000) String notes,
            @NotNull @Size(min = 1, max = 200) List<@NotNull @Valid TaskLineRequest> lines) {
        WmsPutawayManagementService.CreateTaskCommand toCommand() {
            return new WmsPutawayManagementService.CreateTaskCommand(
                    receiptPublicId, taskNumber, priority, plannedAt, notes,
                    lines.stream().map(TaskLineRequest::toCommand).toList());
        }
    }

    public record TaskLineRequest(
            @NotBlank @Size(max = 36) String receiptLinePublicId,
            @Size(max = 36) String lotPublicId,
            @NotBlank @Size(max = 36) String destinationLocationPublicId,
            @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal plannedQty,
            @Size(max = 100) List<@NotBlank @Size(max = 36) String> serialPublicIds,
            @Size(max = 1000) String notes) {
        WmsPutawayManagementService.TaskLineCommand toCommand() {
            return new WmsPutawayManagementService.TaskLineCommand(
                    receiptLinePublicId, lotPublicId, destinationLocationPublicId,
                    plannedQty, serialPublicIds, notes);
        }
    }

    public record AssignTaskRequest(@NotBlank @Size(max = 36) String membershipPublicId) {}

    public record ConfirmPutawayRequest(
            @NotNull UUID requestId,
            @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal confirmedQty,
            @Size(max = 100) List<@NotBlank @Size(max = 36) String> serialPublicIds,
            @Size(max = 1000) String notes) {
        WmsPutawayManagementService.ConfirmPutawayCommand toCommand() {
            return new WmsPutawayManagementService.ConfirmPutawayCommand(
                    requestId, confirmedQty, serialPublicIds, notes);
        }
    }
}
