package com.zirocraft.zirologistics.wms.inbound.http;

import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;

import com.zirocraft.zirologistics.wms.inbound.application.WmsInboundReceivingService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;

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
@RequestMapping("/api/wms/v1/tenants/current/inbound-receipts")
@Conditional(WmsHttpApiEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsInboundReceivingController {
    private final WmsInboundReceivingService service;

    public WmsInboundReceivingController(WmsInboundReceivingService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication authentication) {
        return service.list(principal(authentication));
    }

    @GetMapping("/{receiptPublicId}")
    public Map<String, Object> get(Authentication authentication, @PathVariable String receiptPublicId) {
        return service.get(principal(authentication), receiptPublicId);
    }

    @PostMapping
    public Map<String, Object> create(
            Authentication authentication, @Valid @RequestBody CreateReceiptRequest request) {
        return service.create(principal(authentication), request.toCommand());
    }

    @PostMapping("/{receiptPublicId}/lines")
    public Map<String, Object> addLine(
            Authentication authentication, @PathVariable String receiptPublicId,
            @Valid @RequestBody AddReceiptLineRequest request) {
        return service.addLine(principal(authentication), receiptPublicId, request.toCommand());
    }

    @PostMapping("/{receiptPublicId}/post")
    public Map<String, Object> post(
            Authentication authentication, @PathVariable String receiptPublicId,
            @Valid @RequestBody PostReceiptRequest request) {
        return service.post(principal(authentication), receiptPublicId, request.toCommand());
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object candidate = authentication == null ? null : authentication.getPrincipal();
        if (!(candidate instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record CreateReceiptRequest(
            @NotBlank @Size(max = 36) String warehousePublicId,
            @NotBlank @Size(max = 36) String supplierPublicId,
            @Size(max = 36) String supplierAddressPublicId,
            @NotBlank @Size(max = 36) String receivingLocationPublicId,
            @NotBlank @Size(max = 60) String documentNumber,
            @Size(max = 100) String externalReference,
            @NotBlank @Size(max = 30) String receiptType,
            LocalDateTime expectedAt,
            @Size(max = 1000) String notes) {
        WmsInboundReceivingService.ReceiptCommand toCommand() {
            return new WmsInboundReceivingService.ReceiptCommand(
                    warehousePublicId, supplierPublicId, supplierAddressPublicId,
                    receivingLocationPublicId, documentNumber, externalReference,
                    receiptType, expectedAt, notes);
        }
    }

    public record AddReceiptLineRequest(
            @NotBlank @Size(max = 36) String itemPublicId,
            @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal expectedQty,
            @Size(max = 1000) String notes) {
        WmsInboundReceivingService.ReceiptLineCommand toCommand() {
            return new WmsInboundReceivingService.ReceiptLineCommand(itemPublicId, expectedQty, notes);
        }
    }

    public record PostReceiptRequest(
            @NotNull UUID requestId,
            @NotNull @Size(min = 1, max = 200) List<@NotNull @Valid LineReceiptRequest> lines) {
        WmsInboundReceivingService.PostReceiptCommand toCommand() {
            return new WmsInboundReceivingService.PostReceiptCommand(
                    requestId, lines.stream().map(LineReceiptRequest::toCommand).toList());
        }
    }

    public record LineReceiptRequest(
            @NotBlank @Size(max = 36) String linePublicId,
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal receivedQty,
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal acceptedQty,
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal rejectedQty,
            @Size(max = 500) String rejectionReason,
            @Size(max = 200) List<@NotNull @Valid LotAllocationRequest> lots,
            @Size(max = 200) List<@NotNull @Valid SerialReceiptRequest> serials) {
        WmsInboundReceivingService.LineReceiptCommand toCommand() {
            List<WmsInboundReceivingService.LotAllocation> lotCommands = lots == null ? null
                    : lots.stream().map(LotAllocationRequest::toCommand).toList();
            List<WmsInboundReceivingService.SerialReceipt> serialCommands = serials == null ? null
                    : serials.stream().map(SerialReceiptRequest::toCommand).toList();
            return new WmsInboundReceivingService.LineReceiptCommand(
                    linePublicId, receivedQty, acceptedQty, rejectedQty, rejectionReason,
                    lotCommands, serialCommands);
        }
    }

    public record LotAllocationRequest(
            @NotBlank @Size(max = 36) String lotPublicId,
            @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal quantity) {
        WmsInboundReceivingService.LotAllocation toCommand() {
            return new WmsInboundReceivingService.LotAllocation(lotPublicId, quantity);
        }
    }

    public record SerialReceiptRequest(
            @NotBlank @Size(max = 150) String serialNumber,
            @Size(max = 36) String lotPublicId) {
        WmsInboundReceivingService.SerialReceipt toCommand() {
            return new WmsInboundReceivingService.SerialReceipt(serialNumber, lotPublicId);
        }
    }
}
