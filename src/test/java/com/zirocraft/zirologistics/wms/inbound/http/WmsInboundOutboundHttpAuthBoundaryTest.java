package com.zirocraft.zirologistics.wms.inbound.http;

import com.zirocraft.zirologistics.wms.inbound.application.WmsInboundReceivingService;
import com.zirocraft.zirologistics.wms.outbound.application.WmsOutboundShipmentService;
import com.zirocraft.zirologistics.wms.outbound.http.WmsOutboundShipmentController;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.verifyNoInteractions;
import org.springframework.http.MediaType;

@WebMvcTest(controllers = {WmsInboundReceivingController.class, WmsOutboundShipmentController.class})
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsInboundReceivingController.class,
        WmsOutboundShipmentController.class, WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {"wms.security.jwt.enabled=true","wms.security.http.auth-endpoints-enabled=false"})
class WmsInboundOutboundHttpAuthBoundaryTest {
    @Autowired MockMvc mockMvc;
    @MockBean(name="wmsAccessJwtDecoder") JwtDecoder accessJwtDecoder;
    @MockBean(name="wmsTenantSelectionJwtDecoder") JwtDecoder tenantSelectionDecoder;
    @MockBean WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean WmsInboundReceivingService inboundService;
    @MockBean WmsOutboundShipmentService outboundService;

    @Test void inboundReceiptListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/inbound-receipts"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(inboundService);
    }
    @Test void inboundReceiptCreateRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/wms/v1/tenants/current/inbound-receipts")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(inboundService);
    }
    @Test void inboundReceiptPostingRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/wms/v1/tenants/current/inbound-receipts/receipt-id/post")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(inboundService);
    }
    @Test void outboundShipmentListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/outbound-shipments"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(outboundService);
    }
    @Test void outboundShipmentCreateRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/wms/v1/tenants/current/outbound-shipments")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(outboundService);
    }
    @Test void outboundShipmentAllocationRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/wms/v1/tenants/current/outbound-shipments/shipment-id/lines/line-id/allocations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(outboundService);
    }
}