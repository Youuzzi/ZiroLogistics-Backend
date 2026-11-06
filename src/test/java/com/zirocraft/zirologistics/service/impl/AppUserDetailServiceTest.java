package com.zirocraft.zirologistics.service.impl;

import com.zirocraft.zirologistics.entity.UserEntity;
import com.zirocraft.zirologistics.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppUserDetailServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AppUserDetailService appUserDetailService;

    @Test
    void inactiveAccountMustBeReturnedAsDisabledUserDetails() {
        UserEntity inactiveUser = UserEntity.builder()
                .email("inactive-user@example.com")
                .password("encoded-password")
                .name("Inactive User")
                .role("ROLE_STAFF")
                .active(false)
                .build();

        when(userRepository.findByEmail("inactive-user@example.com"))
                .thenReturn(Optional.of(inactiveUser));

        UserDetails userDetails =
                appUserDetailService.loadUserByUsername("inactive-user@example.com");

        assertFalse(userDetails.isEnabled(),
                "An inactive database account must not be enabled in Spring Security");
    }
}
