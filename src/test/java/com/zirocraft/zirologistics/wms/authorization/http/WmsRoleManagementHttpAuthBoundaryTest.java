package com.zirocraft.zirologistics.wms.authorization.http;

import com.zirocraft.zirologistics.wms.authorization.application.WmsRoleManagementService;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsRoleManagementController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsRoleManagementController.class,
        WmsAuthenticationExceptionHandler.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {"wms.security.jwt.enabled=true","wms.security.http.auth-endpoints-enabled=false"})
class WmsRoleManagementHttpAuthBoundaryTest {
    @Autowired MockMvc mockMvc;
    @MockBean(name="wmsAccessJwtDecoder") JwtDecoder accessJwtDecoder;
    @MockBean(name="wmsTenantSelectionJwtDecoder") JwtDecoder tenantSelectionDecoder;
    @MockBean WmsJwtAuthenticationConverter authenticationConverter;
    @MockBean WmsRoleManagementService service;
    private static final String ROOT="/api/wms/v1/tenants/current";

    @Test void roleListRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROOT+"/roles")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void roleCreateRequiresAuthentication() throws Exception {
        mockMvc.perform(post(ROOT+"/roles").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void roleUpdateRequiresAuthentication() throws Exception {
        mockMvc.perform(put(ROOT+"/roles/role-id").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void roleDeactivateRequiresAuthentication() throws Exception {
        mockMvc.perform(post(ROOT+"/roles/role-id/deactivate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void permissionCatalogueRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROOT+"/permissions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void permissionReplacementRequiresAuthentication() throws Exception {
        mockMvc.perform(put(ROOT+"/roles/role-id/permissions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void membershipRoleListRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ROOT+"/memberships/membership-id/roles")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void membershipRoleAssignmentRequiresAuthentication() throws Exception {
        mockMvc.perform(post(ROOT+"/memberships/membership-id/roles/role-id"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void membershipRoleRemovalRequiresAuthentication() throws Exception {
        mockMvc.perform(delete(ROOT+"/memberships/membership-id/roles/role-id"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}