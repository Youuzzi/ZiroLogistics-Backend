package com.zirocraft.zirologistics.wms.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsCorsSecurityIntegrationTest.ProbeController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import({WmsSecurityConfiguration.class, WmsCorsConfiguration.class, WmsCorsSecurityIntegrationTest.ProbeController.class})
@ActiveProfiles("wms")
@TestPropertySource(properties = {
        "wms.security.jwt.enabled=false",
        "wms.security.cors.allowed-origins=http://localhost:5173"
})
class WmsCorsSecurityIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String FOREIGN_ORIGIN = "https://foreign-example.com";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightFromConfiguredFrontendOriginMustBeAccepted() throws Exception {
        mockMvc.perform(options("/api/wms/v1/warehouses")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsString("Authorization")));
    }

    @Test
    void preflightFromUnconfiguredOriginMustBeRejected() throws Exception {
        mockMvc.perform(options("/api/wms/v1/warehouses")
                        .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void actualRequestFromConfiguredFrontendOriginReceivesCorsHeadersWithoutBypassingAuthentication()
            throws Exception {
        mockMvc.perform(get("/wms/cors-probe")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @RestController
    static class ProbeController {
        @GetMapping(value = "/wms/cors-probe", produces = MediaType.TEXT_PLAIN_VALUE)
        String probe() {
            return "must-remain-authenticated";
        }
    }
}
