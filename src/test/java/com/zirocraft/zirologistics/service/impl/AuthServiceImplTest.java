package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.UserEntity;
import com.zirocraft.zirologistics.io.request.AuthRequest;
import com.zirocraft.zirologistics.repository.UserRepository;
import com.zirocraft.zirologistics.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private AppUserDetailService appUserDetailService;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void registerMustIgnoreCallerSuppliedAdminRole() {
        AuthRequest request = new AuthRequest();
        request.setEmail("new-user@example.com");
        request.setPassword("Password1");
        request.setName("New User");
        request.setRole("ROLE_ADMIN");

        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded-password");
        when(userRepository.save(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        authService.register(request);

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        org.mockito.Mockito.verify(userRepository).save(userCaptor.capture());

        assertEquals("ROLE_STAFF", userCaptor.getValue().getRole(),
                "Public registration must not allow callers to assign privileged roles");
    }
}
