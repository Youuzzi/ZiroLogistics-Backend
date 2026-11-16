package com.zirocraft.zirologistics.wms.tenancy.http;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProfile;
import com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProfileService;
import com.zirocraft.zirologistics.wms.tenancy.domain.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = WmsTenantProfileController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsTenantProfileController.class,
        WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=true"
})
class WmsTenantProfileControllerHttpTest {
    private static final String ROUTE = "/api/wms/v1/tenants/current/profile";

    @Autowired private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;
    @MockBean(name = "wmsTenantSelectionJwtDecoder")
    private JwtDecoder tenantSelectionDecoder;
    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean
    private WmsTenantProfileService profileService;

    @Test
    void profileRouteRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROUTE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readsOnlyTheAuthenticatedCurrentTenantProfile() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();
        when(profileService.getCurrent(principal)).thenReturn(profile());

        mockMvc.perform(get(ROUTE).with(authentication(authenticated(principal))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value("tenant-public-id"))
                .andExpect(jsonPath("$.tenantCode").value("TENANT_A"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(4));

        verify(profileService).getCurrent(principal);
    }

    @Test
    void updatesOnlyTheProfileFieldsAndCarriesOptimisticVersion() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();
        when(profileService.updateCurrent(eq(principal), any())).thenReturn(profile());

        mockMvc.perform(put(ROUTE)
                        .with(authentication(authenticated(principal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName":"Updated Legal Entity",
                                  "displayName":"Updated Display",
                                  "timezone":"Asia/Jakarta",
                                  "defaultCurrency":"IDR",
                                  "expectedVersion":3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value("TENANT_A"))
                .andExpect(jsonPath("$.version").value(4));

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.zirocraft.zirologistics.wms.tenancy.application.WmsTenantProfileUpdate.class);
        verify(profileService).updateCurrent(eq(principal), captor.capture());
        assertEquals(3L, captor.getValue().expectedVersion());
        assertEquals("Updated Legal Entity", captor.getValue().legalName());
        assertEquals("Updated Display", captor.getValue().displayName());
    }

    @Test
    void rejectsInvalidCurrencyShapeBeforeCallingService() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();
        mockMvc.perform(put(ROUTE)
                        .with(authentication(authenticated(principal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "legalName":"Updated Legal Entity",
                                  "displayName":"Updated Display",
                                  "timezone":"Asia/Jakarta",
                                  "defaultCurrency":"IDRR",
                                  "expectedVersion":3
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(profileService);
    }

    private static UsernamePasswordAuthenticationToken authenticated(WmsAuthenticatedPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static WmsAuthenticatedPrincipal principal() {
        return new WmsAuthenticatedPrincipal(
                "user-public-id", 11L, "tenant-public-id", 21L, "membership-public-id", Set.of());
    }

    private static WmsTenantProfile profile() {
        return new WmsTenantProfile(
                "tenant-public-id", "TENANT_A", "Updated Legal Entity", "Updated Display",
                TenantStatus.ACTIVE, "Asia/Jakarta", "IDR", 4L);
    }
}
