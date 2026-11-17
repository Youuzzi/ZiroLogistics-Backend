package com.zirocraft.zirologistics.wms.transfer.http;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsIdentityHttpEnabledCondition;
import com.zirocraft.zirologistics.wms.transfer.application.WmsInterWarehouseTransferService;
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
@RequestMapping("/api/wms/v1/tenants/current/stock-transfers")
@Conditional(WmsIdentityHttpEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsInterWarehouseTransferController {
    private final WmsInterWarehouseTransferService service;

    public WmsInterWarehouseTransferController(WmsInterWarehouseTransferService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String,Object>> list(Authentication authentication) {
        return service.list(principal(authentication));
    }

    @GetMapping("/{transferPublicId}")
    public Map<String,Object> get(Authentication authentication,@PathVariable String transferPublicId) {
        return service.get(principal(authentication),transferPublicId);
    }

    @PostMapping
    public Map<String,Object> create(Authentication authentication,@Valid @RequestBody CreateTransferRequest request) {
        return service.create(principal(authentication),request.toCommand());
    }

    @PostMapping("/{transferPublicId}/release")
    public Map<String,Object> release(Authentication authentication,@PathVariable String transferPublicId,
                                      @Valid @RequestBody ReleaseRequest request) {
        return service.release(principal(authentication),transferPublicId,request.requestId());
    }

    @PostMapping("/{transferPublicId}/dispatch")
    public Map<String,Object> dispatch(Authentication authentication,@PathVariable String transferPublicId,
                                       @Valid @RequestBody DispatchRequest request) {
        return service.dispatch(principal(authentication),transferPublicId,request.toCommand());
    }

    @PostMapping("/{transferPublicId}/receive")
    public Map<String,Object> receive(Authentication authentication,@PathVariable String transferPublicId,
                                      @Valid @RequestBody ReceiveRequest request) {
        return service.receive(principal(authentication),transferPublicId,request.toCommand());
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object candidate=authentication==null?null:authentication.getPrincipal();
        if(!(candidate instanceof WmsAuthenticatedPrincipal principal))
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        return principal;
    }

    public record CreateTransferRequest(
            @NotBlank @Size(max=36) String sourceWarehousePublicId,
            @NotBlank @Size(max=36) String destinationWarehousePublicId,
            @NotBlank @Size(max=60) String documentNumber,
            @Size(max=100) String externalReference,
            @NotBlank @Size(max=30) String transferType,
            LocalDateTime scheduledAt,
            @Size(max=1000) String notes,
            @NotNull @Size(min=1,max=200) List<@NotNull @Valid TransferLineRequest> lines) {
        WmsInterWarehouseTransferService.TransferCommand toCommand() {
            return new WmsInterWarehouseTransferService.TransferCommand(sourceWarehousePublicId,destinationWarehousePublicId,
                    documentNumber,externalReference,transferType,scheduledAt,notes,lines.stream().map(TransferLineRequest::toCommand).toList());
        }
    }

    public record TransferLineRequest(
            @NotBlank @Size(max=36) String itemPublicId,
            @NotNull @DecimalMin("0.0001") @Digits(integer=15,fraction=4) BigDecimal requestedQty,
            @Size(max=1000) String notes,
            @NotNull @Size(min=1,max=200) List<@NotNull @Valid TransferAllocationRequest> allocations) {
        WmsInterWarehouseTransferService.LineCommand toCommand() {
            return new WmsInterWarehouseTransferService.LineCommand(itemPublicId,requestedQty,notes,
                    allocations.stream().map(TransferAllocationRequest::toCommand).toList());
        }
    }

    public record TransferAllocationRequest(
            @NotBlank @Size(max=36) String sourceLocationPublicId,
            @NotBlank @Size(max=36) String destinationLocationPublicId,
            @Size(max=36) String lotPublicId,
            @NotNull @DecimalMin("0.0001") @Digits(integer=15,fraction=4) BigDecimal plannedQty,
            @Size(max=200) List<@NotBlank @Size(max=36) String> serialPublicIds) {
        WmsInterWarehouseTransferService.AllocationCommand toCommand() {
            return new WmsInterWarehouseTransferService.AllocationCommand(sourceLocationPublicId,destinationLocationPublicId,
                    lotPublicId,plannedQty,serialPublicIds);
        }
    }

    public record ReleaseRequest(@NotNull UUID requestId) {}

    public record DispatchRequest(
            @NotNull UUID requestId,
            @NotNull @Size(min=1,max=200) List<@NotNull @Valid DispatchAllocationRequest> allocations,
            @Size(max=1000) String notes) {
        WmsInterWarehouseTransferService.DispatchCommand toCommand() {
            return new WmsInterWarehouseTransferService.DispatchCommand(requestId,
                    allocations.stream().map(DispatchAllocationRequest::toCommand).toList(),notes);
        }
    }

    public record DispatchAllocationRequest(
            @NotBlank @Size(max=36) String allocationPublicId,
            @NotNull @DecimalMin("0.0001") @Digits(integer=15,fraction=4) BigDecimal quantity,
            @Size(max=200) List<@NotBlank @Size(max=36) String> serialPublicIds) {
        WmsInterWarehouseTransferService.DispatchAllocation toCommand() {
            return new WmsInterWarehouseTransferService.DispatchAllocation(allocationPublicId,quantity,serialPublicIds);
        }
    }

    public record ReceiveRequest(
            @NotNull UUID requestId,
            @NotNull @Size(min=1,max=200) List<@NotNull @Valid ReceiveAllocationRequest> allocations,
            @Size(max=1000) String notes) {
        WmsInterWarehouseTransferService.ReceiveCommand toCommand() {
            return new WmsInterWarehouseTransferService.ReceiveCommand(requestId,
                    allocations.stream().map(ReceiveAllocationRequest::toCommand).toList(),notes);
        }
    }

    public record ReceiveAllocationRequest(
            @NotBlank @Size(max=36) String allocationPublicId,
            @NotNull @DecimalMin("0.0") @Digits(integer=15,fraction=4) BigDecimal receivedQty,
            @NotNull @DecimalMin("0.0") @Digits(integer=15,fraction=4) BigDecimal damagedQty,
            @NotNull @DecimalMin("0.0") @Digits(integer=15,fraction=4) BigDecimal shortQty,
            @Size(max=200) List<@NotBlank @Size(max=36) String> receivedSerialPublicIds,
            @Size(max=200) List<@NotBlank @Size(max=36) String> damagedSerialPublicIds,
            @Size(max=200) List<@NotBlank @Size(max=36) String> shortSerialPublicIds) {
        WmsInterWarehouseTransferService.ReceiveAllocation toCommand() {
            return new WmsInterWarehouseTransferService.ReceiveAllocation(allocationPublicId,receivedQty,damagedQty,shortQty,
                    receivedSerialPublicIds,damagedSerialPublicIds,shortSerialPublicIds);
        }
    }
}
