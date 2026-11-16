package com.zirocraft.zirologistics.wms.authorization.http;

import com.zirocraft.zirologistics.wms.authorization.application.WmsRoleManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsIdentityHttpEnabledCondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current")
@Conditional(WmsIdentityHttpEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsRoleManagementController {
    private final WmsRoleManagementService service;

    public WmsRoleManagementController(WmsRoleManagementService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> listRoles(Authentication authentication) {
        return service.listRoles(principal(authentication));
    }

    @PostMapping("/roles")
    public Map<String, Object> createRole(
            Authentication authentication, @Valid @RequestBody CreateRoleRequest request) {
        return service.createRole(principal(authentication), request.roleCode(),
                request.roleName(), request.description());
    }

    @PutMapping("/roles/{rolePublicId}")
    public Map<String, Object> updateRole(
            Authentication authentication, @PathVariable String rolePublicId,
            @Valid @RequestBody UpdateRoleRequest request) {
        return service.updateRole(principal(authentication), rolePublicId,
                request.roleName(), request.description(), request.expectedVersion());
    }

    @PostMapping("/roles/{rolePublicId}/deactivate")
    public void deactivateRole(
            Authentication authentication, @PathVariable String rolePublicId,
            @Valid @RequestBody VersionRequest request) {
        service.deactivateRole(principal(authentication), rolePublicId, request.expectedVersion());
    }

    @GetMapping("/permissions")
    public List<Map<String, Object>> permissionCatalogue(Authentication authentication) {
        return service.permissionCatalogue(principal(authentication));
    }

    @PutMapping("/roles/{rolePublicId}/permissions")
    public List<String> replacePermissions(
            Authentication authentication, @PathVariable String rolePublicId,
            @Valid @RequestBody ReplacePermissionsRequest request) {
        return service.replaceRolePermissions(principal(authentication), rolePublicId,
                request.permissionCodes(), request.expectedVersion());
    }

    @GetMapping("/memberships/{membershipPublicId}/roles")
    public List<String> membershipRoles(
            Authentication authentication, @PathVariable String membershipPublicId) {
        return service.membershipRoles(principal(authentication), membershipPublicId);
    }

    @PostMapping("/memberships/{membershipPublicId}/roles/{rolePublicId}")
    public void assignRole(
            Authentication authentication, @PathVariable String membershipPublicId,
            @PathVariable String rolePublicId) {
        service.assignRole(principal(authentication), membershipPublicId, rolePublicId);
    }

    @DeleteMapping("/memberships/{membershipPublicId}/roles/{rolePublicId}")
    public void removeRole(
            Authentication authentication, @PathVariable String membershipPublicId,
            @PathVariable String rolePublicId) {
        service.removeRole(principal(authentication), membershipPublicId, rolePublicId);
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object candidate = authentication == null ? null : authentication.getPrincipal();
        if (!(candidate instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record CreateRoleRequest(
            @NotBlank @Size(min = 2, max = 80) String roleCode,
            @NotBlank @Size(min = 2, max = 120) String roleName,
            @Size(max = 255) String description) {}

    public record UpdateRoleRequest(
            @NotBlank @Size(min = 2, max = 120) String roleName,
            @Size(max = 255) String description,
            @NotNull @PositiveOrZero Long expectedVersion) {}

    public record VersionRequest(@NotNull @PositiveOrZero Long expectedVersion) {}

    public record ReplacePermissionsRequest(
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 120) String> permissionCodes,
            @NotNull @PositiveOrZero Long expectedVersion) {}
}
