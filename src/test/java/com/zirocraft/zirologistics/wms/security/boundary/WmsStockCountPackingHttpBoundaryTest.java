package com.zirocraft.zirologistics.wms.security.boundary;

import com.zirocraft.zirologistics.wms.packing.application.WmsPackingManagementService;
import com.zirocraft.zirologistics.wms.packing.http.WmsPackingManagementController;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import com.zirocraft.zirologistics.wms.stockcount.application.WmsStockCountManagementService;
import com.zirocraft.zirologistics.wms.stockcount.http.WmsStockCountManagementController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        WmsStockCountManagementController.class,
        WmsPackingManagementController.class
})
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsStockCountManagementController.class,
        WmsPackingManagementController.class, WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=false"
})
class WmsStockCountPackingHttpBoundaryTest {
    private static final String STOCK_COUNT_ROUTE = "/api/wms/v1/tenants/current/stock-counts";
    private static final String PACKING_ROUTE = "/api/wms/v1/tenants/current/packing-sessions";

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;
    @MockBean(name = "wmsTenantSelectionJwtDecoder")
    private JwtDecoder tenantSelectionDecoder;
    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean
    private WmsStockCountManagementService stockCountService;
    @MockBean
    private WmsPackingManagementService packingService;

    @Test
    void stockCountListRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(get(STOCK_COUNT_ROUTE))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(stockCountService);
    }

    @Test
    void stockCountCreateRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(post(STOCK_COUNT_ROUTE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "warehousePublicId":"warehouse-public-id",
                                  "documentNumber":"COUNT-001",
                                  "countType":"FULL",
                                  "countMode":"BLIND",
                                  "locationPublicIds":["location-public-id"]
                                }
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(stockCountService);
    }

    @Test
    void packingListRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(get(PACKING_ROUTE))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(packingService);
    }

    @Test
    void packingCreateRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(post(PACKING_ROUTE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "shipmentPublicId":"shipment-public-id",
                                  "packingNumber":"PACK-001"
                                }
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(packingService);
    }
}
