package com.zirocraft.zirologistics.wms.packing.http;

import com.zirocraft.zirologistics.wms.packing.application.WmsPackingManagementService;
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
@RequestMapping("/api/wms/v1/tenants/current/packing-sessions")
@Conditional(WmsIdentityHttpEnabledCondition.class)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class WmsPackingManagementController {
    private final WmsPackingManagementService service;
    public WmsPackingManagementController(WmsPackingManagementService service){this.service=service;}
    @GetMapping public List<Map<String,Object>> list(Authentication a){return service.list(principal(a));}
    @GetMapping("/{sessionPublicId}") public Map<String,Object> get(Authentication a,@PathVariable String sessionPublicId){return service.get(principal(a),sessionPublicId);}
    @PostMapping public Map<String,Object> create(Authentication a,@Valid @RequestBody CreateRequest r){return service.create(principal(a),new WmsPackingManagementService.CreateSessionCommand(r.shipmentPublicId(),r.packingNumber(),r.notes()));}
    @PostMapping("/{sessionPublicId}/ready") public Map<String,Object> ready(Authentication a,@PathVariable String sessionPublicId){return service.ready(principal(a),sessionPublicId);}
    @PostMapping("/{sessionPublicId}/assign") public Map<String,Object> assign(Authentication a,@PathVariable String sessionPublicId,@Valid @RequestBody AssignRequest r){return service.assign(principal(a),sessionPublicId,new WmsPackingManagementService.AssignSessionCommand(r.membershipPublicId()));}
    @PostMapping("/{sessionPublicId}/start") public Map<String,Object> start(Authentication a,@PathVariable String sessionPublicId){return service.start(principal(a),sessionPublicId);}
    @PostMapping("/{sessionPublicId}/packages") public Map<String,Object> createPackage(Authentication a,@PathVariable String sessionPublicId,@Valid @RequestBody PackageRequest r){return service.createPackage(principal(a),sessionPublicId,new WmsPackingManagementService.CreatePackageCommand(r.packageNumber(),r.packageType(),r.weight(),r.length(),r.width(),r.height(),r.weightUomCode()));}
    @PostMapping("/{sessionPublicId}/packages/{packagePublicId}/lines") public Map<String,Object> addLine(Authentication a,@PathVariable String sessionPublicId,@PathVariable String packagePublicId,@Valid @RequestBody PackageLineRequest r){return service.addLine(principal(a),sessionPublicId,packagePublicId,new WmsPackingManagementService.AddPackageLineCommand(r.requestId(),r.pickingLineNumber(),r.quantity(),r.serialPublicIds(),r.notes()));}
    @PostMapping("/{sessionPublicId}/packages/{packagePublicId}/seal") public Map<String,Object> seal(Authentication a,@PathVariable String sessionPublicId,@PathVariable String packagePublicId){return service.sealPackage(principal(a),sessionPublicId,packagePublicId);}
    @PostMapping("/{sessionPublicId}/complete") public Map<String,Object> complete(Authentication a,@PathVariable String sessionPublicId){return service.complete(principal(a),sessionPublicId);}
    private static WmsAuthenticatedPrincipal principal(Authentication a){Object p=a==null?null:a.getPrincipal();if(!(p instanceof WmsAuthenticatedPrincipal w))throw new IllegalStateException("Authenticated WMS principal is unavailable");return w;}
    public record CreateRequest(@NotBlank @Size(max=36) String shipmentPublicId,@NotBlank @Size(max=60) String packingNumber,@Size(max=1000) String notes){}
    public record AssignRequest(@NotBlank @Size(max=36) String membershipPublicId){}
    public record PackageRequest(@NotBlank @Size(max=60) String packageNumber,@Size(max=30) String packageType,
                                 @DecimalMin(value="0.0001") @Digits(integer=15,fraction=4) BigDecimal weight,
                                 @DecimalMin(value="0.001") @Digits(integer=9,fraction=3) BigDecimal length,
                                 @DecimalMin(value="0.001") @Digits(integer=9,fraction=3) BigDecimal width,
                                 @DecimalMin(value="0.001") @Digits(integer=9,fraction=3) BigDecimal height,
                                 @Size(max=20) String weightUomCode){}
    public record PackageLineRequest(@NotNull UUID requestId,@Min(1) int pickingLineNumber,
                                     @NotNull @DecimalMin("0.0001") @Digits(integer=15,fraction=4) BigDecimal quantity,
                                     @Size(max=100) List<@NotBlank @Size(max=36) String> serialPublicIds,
                                     @Size(max=1000) String notes){}
}
