package com.zirocraft.zirologistics.wms.inventory.http;

import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryMasterDataService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryPostingService;
import com.zirocraft.zirologistics.wms.inventory.application.WmsInventoryQueryService;
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
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.mockito.Mockito.verifyNoInteractions;

@WebMvcTest(controllers = {WmsInventoryController.class, WmsInventoryMasterDataController.class})
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsInventoryController.class,
        WmsInventoryMasterDataController.class, WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {"wms.security.jwt.enabled=true","wms.security.http.auth-endpoints-enabled=false"})
class WmsInventoryHttpAuthBoundaryTest {
    @Autowired MockMvc mockMvc;
    @MockBean(name="wmsAccessJwtDecoder") JwtDecoder accessJwtDecoder;
    @MockBean(name="wmsTenantSelectionJwtDecoder") JwtDecoder tenantSelectionDecoder;
    @MockBean WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean WmsInventoryPostingService postingService;
    @MockBean WmsInventoryQueryService queryService;
    @MockBean WmsInventoryMasterDataService masterDataService;

    @Test void inventoryBalancesRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/inventory/balances"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(queryService);
    }
    @Test void inventoryLedgerRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/inventory/ledger"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(queryService);
    }
    @Test void inventoryPostingRequiresAuthenticationAndDoesNotInvokePostingService() throws Exception {
        mockMvc.perform(post("/api/wms/v1/tenants/current/inventory/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"2a8e5d0a-15b1-4a68-bcc1-0f0d9e8c0d10\",\"movementType\":\"RECEIPT\",\"legs\":[{}]}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(postingService);
    }
    @Test void itemListingRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/items"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(masterDataService);
    }
    @Test void categoryCreationRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/wms/v1/tenants/current/item-categories")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(masterDataService);
    }
    @Test void uomListingRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/uoms"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(masterDataService);
    }
    @Test void itemDetailRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/items/item-public-id"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(masterDataService);
    }
    @Test void itemUpdateRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/wms/v1/tenants/current/items/item-public-id")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(masterDataService);
    }
    @Test void itemLookupRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/items/lookup?identifierType=SKU&value=SKU-01"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(masterDataService);
    }
    @Test void categoryListRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/wms/v1/tenants/current/item-categories"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(masterDataService);
    }
}