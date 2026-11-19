package com.zirocraft.zirologistics.wms.tenancy.http;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsHttpApiEnabledCondition;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProfile;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProfileService;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProfileUpdate;
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

@RestController
@Profile("wms")
@RequestMapping("/api/wms/v1/tenants/current/profile")
@Conditional(WmsHttpApiEnabledCondition.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsTenantProfileController {
    private final WmsTenantProfileService profileService;

    public WmsTenantProfileController(WmsTenantProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public TenantProfileResponse getCurrent(Authentication authentication) {
        return TenantProfileResponse.from(profileService.getCurrent(principal(authentication)));
    }

    @PutMapping
    public TenantProfileResponse updateCurrent(
            Authentication authentication,
            @Valid @RequestBody TenantProfileUpdateRequest request) {
        return TenantProfileResponse.from(profileService.updateCurrent(
                principal(authentication),
                new WmsTenantProfileUpdate(
                        request.legalName(), request.displayName(), request.timezone(),
                        request.defaultCurrency(), request.expectedVersion())));
    }

    private static WmsAuthenticatedPrincipal principal(Authentication authentication) {
        Object candidate = authentication == null ? null : authentication.getPrincipal();
        if (!(candidate instanceof WmsAuthenticatedPrincipal principal)) {
            throw new IllegalStateException("Authenticated WMS principal is unavailable");
        }
        return principal;
    }

    public record TenantProfileUpdateRequest(
            @NotBlank @Size(max = 200) String legalName,
            @NotBlank @Size(max = 150) String displayName,
            @NotBlank @Size(max = 60) String timezone,
            @NotBlank @Size(min = 3, max = 3) String defaultCurrency,
            @NotNull @PositiveOrZero Long expectedVersion) {
    }

    public record TenantProfileResponse(
            String publicId,
            String tenantCode,
            String legalName,
            String displayName,
            String status,
            String timezone,
            String defaultCurrency,
            long version) {
        static TenantProfileResponse from(WmsTenantProfile profile) {
            return new TenantProfileResponse(
                    profile.publicId(), profile.tenantCode(), profile.legalName(),
                    profile.displayName(), profile.status().name(), profile.timezone(),
                    profile.defaultCurrency(), profile.version());
        }
    }
}
