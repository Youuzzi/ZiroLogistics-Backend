package com.zirocraft.zirologistics.wms.transfer.http;

import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import com.zirocraft.zirologistics.wms.security.authentication.http.WmsAuthenticationExceptionHandler;
import com.zirocraft.zirologistics.wms.security.config.WmsHttpSecurityTestApplication;
import com.zirocraft.zirologistics.wms.security.config.WmsSecurityConfiguration;
import com.zirocraft.zirologistics.wms.transfer.application.WmsInterWarehouseTransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsInterWarehouseTransferController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsInterWarehouseTransferController.class,
        WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {"wms.security.jwt.enabled=true","wms.security.http.auth-endpoints-enabled=false"})
class WmsInterWarehouseTransferHttpAuthBoundaryTest {
    @Autowired MockMvc mockMvc;
    @MockBean(name="wmsAccessJwtDecoder") JwtDecoder accessJwtDecoder;
    @MockBean(name="wmsTenantSelectionJwtDecoder") JwtDecoder tenantSelectionDecoder;
    @MockBean WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean WmsInterWarehouseTransferService service;
    private static final String ROUTE="/api/wms/v1/tenants/current/stock-transfers";

    @Test void transferListRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROUTE)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void transferDetailRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROUTE+"/transfer-id")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void transferCreateRequiresAuthentication() throws Exception {
        mockMvc.perform(post(ROUTE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void transferReleaseRequiresAuthentication() throws Exception {
        mockMvc.perform(post(ROUTE+"/transfer-id/release").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void transferDispatchRequiresAuthentication() throws Exception {
        mockMvc.perform(post(ROUTE+"/transfer-id/dispatch").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void transferReceiveRequiresAuthentication() throws Exception {
        mockMvc.perform(post(ROUTE+"/transfer-id/receive").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}