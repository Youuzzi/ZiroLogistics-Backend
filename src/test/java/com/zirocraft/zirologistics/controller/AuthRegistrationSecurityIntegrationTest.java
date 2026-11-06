package com.zirocraft.zirologistics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zirocraft.zirologistics.entity.UserEntity;
import com.zirocraft.zirologistics.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRegistrationSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Test
    void publicRegistrationMustNotCreateAdminEvenWhenRequestSuppliesAdminRole() throws Exception {
        String email = "role-escalation-" + UUID.randomUUID() + "@example.com";
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", "Password1",
                "name", "Role Escalation Test",
                "role", "ROLE_ADMIN"
        ));

        mockMvc.perform(post("/api/v1.0/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ROLE_STAFF"));

        UserEntity persistedUser = userRepository.findByEmail(email).orElseThrow();
        assertEquals("ROLE_STAFF", persistedUser.getRole(),
                "The public HTTP registration endpoint must persist ROLE_STAFF");
        assertTrue(persistedUser.isActive());
    }
}
