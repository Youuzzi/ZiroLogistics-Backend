package com.zirocraft.zirologistics.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void expectedConflictUsesDeclaredStatusAndSafeMessage() throws Exception {
        mockMvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("Request ID sudah pernah diproses."));
    }

    @Test
    void unexpectedExceptionReturnsGeneric500WithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/probe/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Terjadi kesalahan pada server. Silakan coba lagi."))
                .andExpect(jsonPath("$.message", not(containsString("jdbc:mysql://internal-host"))));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe/conflict")
        void conflict() {
            throw new ApiException(HttpStatus.CONFLICT, "Request ID sudah pernah diproses.");
        }

        @GetMapping("/probe/unexpected")
        void unexpected() {
            throw new IllegalStateException("Database failure at jdbc:mysql://internal-host/private_db");
        }
    }
}
