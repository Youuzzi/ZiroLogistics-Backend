package com.zirocraft.zirologistics.wms.security.boundary;

import com.zirocraft.zirologistics.wms.adjustment.application.WmsInventoryAdjustmentService;
import com.zirocraft.zirologistics.wms.adjustment.http.WmsInventoryAdjustmentController;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import com.zirocraft.zirologistics.wms.shipping.application.WmsShippingManagementService;
import com.zirocraft.zirologistics.wms.shipping.http.WmsShippingManagementController;
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

@WebMvcTest(controllers = {WmsInventoryAdjustmentController.class, WmsShippingManagementController.class})
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsInventoryAdjustmentController.class,
        WmsShippingManagementController.class, WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=false"
})
class WmsAdjustmentShippingHttpBoundaryTest {
    private static final String ADJUSTMENT_ROUTE = "/api/wms/v1/tenants/current/inventory-adjustments";
    private static final String SHIPPING_ROUTE = "/api/wms/v1/tenants/current/shipping";

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;
    @MockBean(name = "wmsTenantSelectionJwtDecoder")
    private JwtDecoder tenantSelectionDecoder;
    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean
    private WmsInventoryAdjustmentService adjustmentService;
    @MockBean
    private WmsShippingManagementService shippingService;

    @Test
    void adjustmentListRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(get(ADJUSTMENT_ROUTE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(adjustmentService);
    }

    @Test
    void manualAdjustmentCreateRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(post(ADJUSTMENT_ROUTE + "/manual")
                        .contentType("application/json")
                        .content("""
                                {
                                  "documentNumber":"ADJ-001",
                                  "adjustmentType":"INCREASE",
                                  "reasonCode":"COUNT_VARIANCE",
                                  "reasonDescription":"Approved stock count variance",
                                  "allocations":[{
                                    "warehousePublicId":"warehouse-public-id",
                                    "locationPublicId":"location-public-id",
                                    "itemPublicId":"item-public-id",
                                    "countedQty":1
                                  }]
                                }
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(adjustmentService);
    }

    @Test
    void shippingListRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(get(SHIPPING_ROUTE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(shippingService);
    }

    @Test
    void shipCommandRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(post(SHIPPING_ROUTE + "/shipment-public-id/ship")
                        .contentType("application/json")
                        .content("""
                                {
                                  "requestId":"0b30efc3-2b6b-4f31-8f63-9ecb78cbe123",
                                  "trackingNumber":"TRACK-001"
                                }
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(shippingService);
    }
}
