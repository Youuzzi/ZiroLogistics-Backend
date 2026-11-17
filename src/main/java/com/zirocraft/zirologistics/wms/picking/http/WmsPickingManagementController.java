package com.zirocraft.zirologistics.wms.picking.http;

import com.zirocraft.zirologistics.wms.picking.application.WmsPickingManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsIdentityHttpEnabledCondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current/picking-tasks")
@Conditional(WmsIdentityHttpEnabledCondition.class)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class WmsPickingManagementController {
    private final WmsPickingManagementService service;
    public WmsPickingManagementController(WmsPickingManagementService service){this.service=service;}
    @GetMapping public List<Map<String,Object>> list(Authentication a){return service.list(principal(a));}
    @GetMapping("/{taskPublicId}") public Map<String,Object> get(Authentication a,@PathVariable String taskPublicId){return service.get(principal(a),taskPublicId);}
    @PostMapping public Map<String,Object> create(Authentication a,@Valid @RequestBody CreateRequest r){return service.create(principal(a),new WmsPickingManagementService.CreatePickingCommand(r.shipmentPublicId(),r.taskNumber(),r.priority(),r.notes()));}
    @PostMapping("/{taskPublicId}/assign") public Map<String,Object> assign(Authentication a,@PathVariable String taskPublicId,@Valid @RequestBody AssignRequest r){return service.assign(principal(a),taskPublicId,new WmsPickingManagementService.AssignPickingCommand(r.membershipPublicId()));}
    @PostMapping("/{taskPublicId}/start") public Map<String,Object> start(Authentication a,@PathVariable String taskPublicId){return service.start(principal(a),taskPublicId);}
    @PostMapping("/{taskPublicId}/lines/{lineNumber}/confirm") public Map<String,Object> confirm(Authentication a,@PathVariable String taskPublicId,@PathVariable @Min(1) int lineNumber,@Valid @RequestBody ConfirmRequest r){return service.confirm(principal(a),taskPublicId,lineNumber,new WmsPickingManagementService.ConfirmPickingCommand(r.requestId(),r.quantity(),r.stagingLocationPublicId(),r.serialPublicIds(),r.notes()));}
    private static WmsAuthenticatedPrincipal principal(Authentication a){Object p=a==null?null:a.getPrincipal();if(!(p instanceof WmsAuthenticatedPrincipal w))throw new IllegalStateException("Authenticated WMS principal is unavailable");return w;}
    public record CreateRequest(@NotBlank @Size(max=36) String shipmentPublicId,@NotBlank @Size(max=60) String taskNumber,@Size(max=20) String priority,@Size(max=1000) String notes){}
    public record AssignRequest(@NotBlank @Size(max=36) String membershipPublicId){}
    public record ConfirmRequest(@NotNull UUID requestId,@NotNull @DecimalMin("0.0001") @Digits(integer=15,fraction=4) BigDecimal quantity,@NotBlank @Size(max=36) String stagingLocationPublicId,@Size(max=100) List<@NotBlank @Size(max=36) String> serialPublicIds,@Size(max=1000) String notes){}
}
