package com.zirocraft.zirologistics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

import java.util.Optional;

@Configuration
public class AuditConfig {

    @Bean
    public AuditorAware<String> auditorProvider() {
        // Nanti kalau sudah ada JWT, ambil email user dari SecurityContextHolder
        return () -> Optional.of("SYSTEM_ADMIN");
    }
}