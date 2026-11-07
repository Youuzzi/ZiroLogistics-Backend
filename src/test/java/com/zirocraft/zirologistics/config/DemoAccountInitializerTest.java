package com.zirocraft.zirologistics.config;

import com.zirocraft.zirologistics.entity.UserEntity;
import com.zirocraft.zirologistics.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DemoAccountInitializerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    @Test
    void createsStaffAccountWithEncodedPasswordWhenAccountDoesNotExist() {
        when(userRepository.findByEmail("demo@example.test")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("DemoPass123")).thenReturn("$2a$encoded");

        DemoAccountInitializer initializer = new DemoAccountInitializer(
                userRepository, passwordEncoder, "demo@example.test", "DemoPass123");

        initializer.run();

        verify(passwordEncoder).encode("DemoPass123");
        verify(userRepository).save(argThat(user ->
                user.getEmail().equals("demo@example.test")
                        && user.getPassword().equals("$2a$encoded")
                        && user.getRole().equals("ROLE_STAFF")
                        && user.isActive()));
    }

    @Test
    void doesNotOverwriteAnExistingAccount() {
        UserEntity existing = UserEntity.builder()
                .email("demo@example.test")
                .password("existing-hash")
                .name("EXISTING USER")
                .role("ROLE_ADMIN")
                .active(false)
                .build();
        when(userRepository.findByEmail("demo@example.test")).thenReturn(Optional.of(existing));

        DemoAccountInitializer initializer = new DemoAccountInitializer(
                userRepository, passwordEncoder, "demo@example.test", "DemoPass123");

        initializer.run();

        verify(userRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void rejectsWeakDemoPassword() {
        DemoAccountInitializer initializer = new DemoAccountInitializer(
                userRepository, passwordEncoder, "demo@example.test", "weak");

        assertThrows(IllegalStateException.class, initializer::run);
        verifyNoInteractions(userRepository, passwordEncoder);
    }
}
