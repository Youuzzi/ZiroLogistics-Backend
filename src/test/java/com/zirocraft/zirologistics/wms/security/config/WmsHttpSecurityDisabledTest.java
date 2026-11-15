package com.zirocraft.zirologistics.wms.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WmsHttpSecurityDisabledTest.ProbeController.class)
@ContextConfiguration(classes = WmsHttpSecurityTestApplication.class)
@Import(WmsSecurityConfiguration.class)
@ActiveProfiles("wms")
@TestPropertySource(properties = "wms.security.jwt.enabled=false")
class WmsHttpSecurityDisabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void deniesEveryRequestWhenWmsJwtIsDisabled() throws Exception {
        mockMvc.perform(get("/wms/security-probe"))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class ProbeController {
        @GetMapping(value = "/wms/security-probe", produces = MediaType.TEXT_PLAIN_VALUE)
        String probe() {
            return "must-not-be-public";
        }
    }
}
