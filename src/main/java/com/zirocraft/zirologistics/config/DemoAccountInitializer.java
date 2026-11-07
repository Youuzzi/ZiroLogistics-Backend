package com.zirocraft.zirologistics.config;

import com.zirocraft.zirologistics.entity.UserEntity;
import com.zirocraft.zirologistics.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/**
 * Creates an isolated, least-privilege demo account only when the demo profile
 * is explicitly activated. Existing accounts are never modified.
 */
@Slf4j
@Component
@Profile("demo")
@ConditionalOnProperty(prefix = "demo.account", name = "enabled", havingValue = "true")
public class DemoAccountInitializer implements CommandLineRunner {

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[0-9])(?=.*[A-Z]).{8,}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public DemoAccountInitializer(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @org.springframework.beans.factory.annotation.Value("${demo.account.email}") String email,
            @org.springframework.beans.factory.annotation.Value("${demo.account.password}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("demo.account.email must be configured when the demo profile is enabled.");
        }
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new IllegalStateException(
                    "Demo password must contain at least 8 characters, one uppercase letter, and one number.");
        }

        if (userRepository.findByEmail(email).isPresent()) {
            log.info("Demo account already exists; leaving the existing account unchanged.");
            return;
        }

        UserEntity demoUser = UserEntity.builder()
                .email(email.trim())
                .password(passwordEncoder.encode(password))
                .name("DEMO USER")
                .role("ROLE_STAFF")
                .active(true)
                .build();

        userRepository.save(demoUser);
        log.info("Created least-privilege demo account for the configured email.");
    }
}
