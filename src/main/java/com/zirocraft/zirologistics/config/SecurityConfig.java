package com.zirocraft.zirologistics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // WAJIB: Biar Postman bisa kirim data (POST/PUT/DELETE)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1.0/**").permitAll() // BUKA GERBANG: Izinkan semua API untuk testing
                        .anyRequest().authenticated()
                );

        return http.build();
    }
}