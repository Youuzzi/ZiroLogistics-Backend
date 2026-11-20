package com.zirocraft.zirologistics.wms.authorization.http;

import com.zirocraft.zirologistics.wms.authorization.application.WmsRoleManagementService;
import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
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
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = WmsRoleManagementController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsRoleManagementController.class, WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=false"
})
class WmsRoleManagementControllerHttpTest {
    private static final String ROUTE = "/api/wms/v1/tenants/current/roles";

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;
    @MockBean(name = "wmsTenantSelectionJwtDecoder")
    private JwtDecoder tenantSelectionDecoder;
    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean
    private WmsRoleManagementService service;

    @Test
    void rolesRouteRequiresAuthenticationWhenPublicAuthEndpointsAreDisabled() throws Exception {
        mockMvc.perform(get(ROUTE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rolePermissionMutationRequiresAuthentication() throws Exception {
        mockMvc.perform(put(ROUTE + "/role-public-id/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"permissionCodes":["WMS_INVENTORY_POST"],"expectedVersion":0}
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void appliesDefaultPaginationToRoleCollection() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();
        when(service.listRoles(principal, 0, 200)).thenReturn(List.of(
                Map.of("publicId", "role-public-id", "roleCode", "SUPERVISOR")));

        mockMvc.perform(get(ROUTE).with(authentication(authenticated(principal))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicId").value("role-public-id"));

        verify(service).listRoles(principal, 0, 200);
    }

    @Test
    void forwardsExplicitRolePaginationParameters() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();
        when(service.listRoles(principal, 2, 50)).thenReturn(List.of());

        mockMvc.perform(get(ROUTE + "?page=2&size=50")
                        .with(authentication(authenticated(principal))))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(service).listRoles(principal, 2, 50);
    }

    @Test
    void mapsServicePageBoundRejectionToStableBadRequestContract() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();
        when(service.listRoles(principal, -1, 200)).thenThrow(new IllegalArgumentException("page must be non-negative"));

        mockMvc.perform(get(ROUTE + "?page=-1")
                        .with(authentication(authenticated(principal))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void validatesRoleCreationRequestBeforeCallingService() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();

        mockMvc.perform(post(ROUTE)
                        .with(authentication(authenticated(principal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleCode":"",
                                  "roleName":"Supervisor"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    private static UsernamePasswordAuthenticationToken authenticated(WmsAuthenticatedPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static WmsAuthenticatedPrincipal principal() {
        return new WmsAuthenticatedPrincipal(
                "user-public-id", 11L, "tenant-public-id", 21L, "membership-public-id", Set.of());
    }
}
