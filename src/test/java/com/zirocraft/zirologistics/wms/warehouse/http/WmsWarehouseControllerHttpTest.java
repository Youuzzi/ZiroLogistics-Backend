package com.zirocraft.zirologistics.wms.warehouse.http;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import com.zirocraft.zirologistics.wms.warehouse.application.WmsWarehouseManagementService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = WmsWarehouseController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsWarehouseController.class, WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=false"
})
class WmsWarehouseControllerHttpTest {
    private static final String ROUTE = "/api/wms/v1/tenants/current/warehouses";

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;
    @MockBean(name = "wmsTenantSelectionJwtDecoder")
    private JwtDecoder tenantSelectionDecoder;
    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean
    private WmsWarehouseManagementService service;

    @Test
    void protectedWarehouseRouteRemainsRegisteredWhenPublicAuthEndpointsAreDisabled() throws Exception {
        mockMvc.perform(get(ROUTE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void warehouseMutationRequiresAuthentication() throws Exception {
        mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void listsOnlyTheAuthenticatedTenantWarehouseServiceResult() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();
        when(service.listWarehouses(principal)).thenReturn(List.of(Map.of(
                "publicId", "warehouse-public-id",
                "warehouseCode", "WH-01",
                "warehouseName", "Main Warehouse")));

        mockMvc.perform(get(ROUTE).with(authentication(authenticated(principal))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicId").value("warehouse-public-id"))
                .andExpect(jsonPath("$[0].warehouseCode").value("WH-01"));

        verify(service).listWarehouses(principal);
    }

    @Test
    void rejectsInvalidWarehouseCountryCodeBeforeCallingService() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();

        mockMvc.perform(post(ROUTE)
                        .with(authentication(authenticated(principal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"WH-01",
                                  "name":"Main Warehouse",
                                  "countryCode":"IDN",
                                  "timezone":"Asia/Jakarta"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    void createsWarehouseUsingOnlyValidatedRequestFieldsAndAuthenticatedPrincipal() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();
        when(service.createWarehouse(eq(principal), any(WmsWarehouseManagementService.WarehouseCommand.class)))
                .thenReturn(Map.of("publicId", "warehouse-public-id", "warehouseCode", "WH-01"));

        mockMvc.perform(post(ROUTE)
                        .with(authentication(authenticated(principal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"WH-01",
                                  "name":"Main Warehouse",
                                  "countryCode":"ID",
                                  "timezone":"Asia/Jakarta"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value("warehouse-public-id"))
                .andExpect(jsonPath("$.warehouseCode").value("WH-01"));

        var captor = org.mockito.ArgumentCaptor.forClass(WmsWarehouseManagementService.WarehouseCommand.class);
        verify(service).createWarehouse(eq(principal), captor.capture());
        assertEquals("WH-01", captor.getValue().code());
        assertEquals("Main Warehouse", captor.getValue().name());
        assertEquals("ID", captor.getValue().countryCode());
        assertEquals("Asia/Jakarta", captor.getValue().timezone());
    }

    private static UsernamePasswordAuthenticationToken authenticated(WmsAuthenticatedPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private static WmsAuthenticatedPrincipal principal() {
        return new WmsAuthenticatedPrincipal(
                "user-public-id", 11L, "tenant-public-id", 21L, "membership-public-id", Set.of());
    }
}
