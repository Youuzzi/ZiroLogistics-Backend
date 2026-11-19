package com.zirocraft.zirologistics.wms.transfer.http;

import com.zirocraft.zirologistics.wms.security.application.WmsAuthenticatedPrincipal;
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

@WebMvcTest(controllers = WmsInterWarehouseTransferController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsInterWarehouseTransferController.class,
        WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=true",
        "wms.security.http.auth-endpoints-enabled=false"
})
class WmsInterWarehouseTransferControllerHttpTest {
    private static final String ROUTE = "/api/wms/v1/tenants/current/stock-transfers";

    @Autowired
    private MockMvc mockMvc;

    @MockBean(name = "wmsAccessJwtDecoder")
    private JwtDecoder accessJwtDecoder;
    @MockBean(name = "wmsTenantSelectionJwtDecoder")
    private JwtDecoder tenantSelectionDecoder;
    @MockBean
    private WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean
    private WmsInterWarehouseTransferService service;

    @Test
    void transferCollectionRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROUTE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forwardsBoundedTransferPaginationParameters() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();
        when(service.list(principal, 3, 25)).thenReturn(List.of(
                Map.of("publicId", "transfer-public-id", "documentNumber", "TR-001")));

        mockMvc.perform(get(ROUTE + "?page=3&size=25")
                        .with(authentication(authenticated(principal))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicId").value("transfer-public-id"));

        verify(service).list(principal, 3, 25);
    }

    @Test
    void rejectsTransferCreationWithEmptyLinesBeforeCallingService() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();

        mockMvc.perform(post(ROUTE)
                        .with(authentication(authenticated(principal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId":"2a8e5d0a-15b1-4a68-bcc1-0f0d9e8c0d10",
                                  "sourceWarehousePublicId":"warehouse-source",
                                  "destinationWarehousePublicId":"warehouse-destination",
                                  "documentNumber":"TR-001",
                                  "transferType":"STANDARD",
                                  "lines":[]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsNestedAllocationWithZeroPlannedQuantityBeforeCallingService() throws Exception {
        WmsAuthenticatedPrincipal principal = principal();

        mockMvc.perform(post(ROUTE)
                        .with(authentication(authenticated(principal)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId":"2a8e5d0a-15b1-4a68-bcc1-0f0d9e8c0d10",
                                  "sourceWarehousePublicId":"warehouse-source",
                                  "destinationWarehousePublicId":"warehouse-destination",
                                  "documentNumber":"TR-001",
                                  "transferType":"STANDARD",
                                  "lines":[{
                                    "itemPublicId":"item-public-id",
                                    "requestedQty":1,
                                    "allocations":[{
                                      "sourceLocationPublicId":"source-location",
                                      "destinationLocationPublicId":"destination-location",
                                      "plannedQty":0
                                    }]
                                  }]
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
