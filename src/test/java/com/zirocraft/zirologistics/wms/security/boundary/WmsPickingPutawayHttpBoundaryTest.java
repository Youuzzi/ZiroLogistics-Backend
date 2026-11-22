package com.zirocraft.zirologistics.wms.security.boundary;

import com.zirocraft.zirologistics.wms.picking.application.WmsPickingManagementService;
import com.zirocraft.zirologistics.wms.picking.http.WmsPickingManagementController;
import com.zirocraft.zirologistics.wms.putaway.application.WmsPutawayManagementService;
import com.zirocraft.zirologistics.wms.putaway.http.WmsPutawayManagementController;
import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
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

@WebMvcTest(controllers = {WmsPickingManagementController.class, WmsPutawayManagementController.class})
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsPickingManagementController.class,
        WmsPutawayManagementController.class, WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=false"
})
class WmsPickingPutawayHttpBoundaryTest {
    private static final String PICKING_ROUTE = "/api/wms/v1/tenants/current/picking-tasks";
    private static final String PUTAWAY_ROUTE = "/api/wms/v1/tenants/current/putaway-tasks";

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;
    @MockBean(name = "wmsTenantSelectionJwtDecoder")
    private JwtDecoder tenantSelectionDecoder;
    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean
    private WmsPickingManagementService pickingService;
    @MockBean
    private WmsPutawayManagementService putawayService;

    @Test
    void pickingListRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(get(PICKING_ROUTE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(pickingService);
    }

    @Test
    void pickingCreateRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(post(PICKING_ROUTE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "shipmentPublicId":"shipment-public-id",
                                  "taskNumber":"PICK-001"
                                }
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(pickingService);
    }

    @Test
    void putawayListRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(get(PUTAWAY_ROUTE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(putawayService);
    }

    @Test
    void putawayCreateRequiresAuthenticationAndDoesNotInvokeService() throws Exception {
        mockMvc.perform(post(PUTAWAY_ROUTE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "receiptPublicId":"receipt-public-id",
                                  "taskNumber":"PUT-001",
                                  "lines":[{
                                    "receiptLinePublicId":"receipt-line-public-id",
                                    "destinationLocationPublicId":"location-public-id",
                                    "plannedQty":1
                                  }]
                                }
                                """))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(putawayService);
    }
}
