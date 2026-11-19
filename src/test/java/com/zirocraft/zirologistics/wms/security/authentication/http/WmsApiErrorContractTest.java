package com.zirocraft.zirologistics.wms.security.authentication.http;

import com.zirocraft.zirologistics.exception.GlobalExceptionHandler;
import com.zirocraft.zirologistics.wms.application.WmsResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WmsApiErrorContractTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkflowProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(), new WmsAuthenticationExceptionHandler())
                .build();
    }

    @Test
    void workflowValidationFailureUsesStableBadRequestContract() throws Exception {
        mockMvc.perform(get("/wms-probe/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("The request is invalid"));
    }

    @Test
    void missingTenantScopedResourceUsesStableNotFoundContract() throws Exception {
        mockMvc.perform(get("/wms-probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void workflowStateConflictUsesStableConflictContract() throws Exception {
        mockMvc.perform(get("/wms-probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CONFLICT"));
    }

    @Test
    void accessDeniedUsesStableForbiddenContract() throws Exception {
        mockMvc.perform(get("/wms-probe/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void malformedJsonUsesStableBadRequestContract() throws Exception {
        mockMvc.perform(post("/wms-probe/body").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void integrityConflictUsesStableConflictContract() throws Exception {
        mockMvc.perform(get("/wms-probe/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CONFLICT"));
    }

    @Test
    void unexpectedFailureDoesNotLeakInternalDetails() throws Exception {
        mockMvc.perform(get("/wms-probe/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message", not(containsString("private_db"))));
    }

    @RestController
    static class WorkflowProbeController {
        @GetMapping("/wms-probe/invalid")
        void invalid() { throw new IllegalArgumentException("private validation detail"); }

        @GetMapping("/wms-probe/not-found")
        void notFound() { throw new WmsResourceNotFoundException("private resource identifier"); }

        @GetMapping("/wms-probe/conflict")
        void conflict() { throw new IllegalStateException("private workflow state"); }

        @GetMapping("/wms-probe/forbidden")
        void forbidden() { throw new AccessDeniedException("private authorization detail"); }

        @PostMapping("/wms-probe/body")
        void body(@RequestBody ProbeRequest request) {}

        @GetMapping("/wms-probe/integrity")
        void integrity() { throw new DataIntegrityViolationException("duplicate key in private_db"); }

        @GetMapping("/wms-probe/unexpected")
        void unexpected() { throw new RuntimeException("jdbc:mysql://internal/private_db"); }
    }

    record ProbeRequest(String value) {}
}
