package com.zirocraft.zirologistics.controller;

import com.zirocraft.zirologistics.entity.UserEntity;
import com.zirocraft.zirologistics.repository.UserRepository;
import com.zirocraft.zirologistics.service.impl.AppUserDetailService;
import com.zirocraft.zirologistics.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InactiveAccountJwtSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AppUserDetailService userDetailsService;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void inactiveAccountMustNotAccessProtectedEndpointWithPreviouslyIssuedJwt() throws Exception {
        String email = "inactive-jwt-" + UUID.randomUUID() + "@example.com";
        UserEntity inactiveUser = UserEntity.builder()
                .email(email)
                .password("encoded-password")
                .name("Inactive JWT User")
                .role("ROLE_STAFF")
                .active(false)
                .build();
        userRepository.save(inactiveUser);

        String token = jwtUtil.generateToken(userDetailsService.loadUserByUsername(email));

        mockMvc.perform(get("/api/v1.0/warehouses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void activeAccountCanAccessProtectedEndpointWithValidJwt() throws Exception {
        String email = "active-jwt-" + UUID.randomUUID() + "@example.com";
        UserEntity activeUser = UserEntity.builder()
                .email(email)
                .password("encoded-password")
                .name("Active JWT User")
                .role("ROLE_STAFF")
                .active(true)
                .build();
        userRepository.save(activeUser);

        String token = jwtUtil.generateToken(userDetailsService.loadUserByUsername(email));

        mockMvc.perform(get("/api/v1.0/warehouses")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
