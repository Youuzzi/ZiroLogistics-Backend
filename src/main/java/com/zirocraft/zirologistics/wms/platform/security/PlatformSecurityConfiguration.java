package com.zirocraft.zirologistics.wms.platform.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Dedicated control-plane HTTP boundary. Its matcher is ordered before the tenant catch-all.
 * No platform route is public in this slice; even future endpoints require platform authentication.
 */
@Configuration(proxyBeanMethods = false)
@Profile("wms")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "wms.platform.security.jwt", name = "enabled", havingValue = "true")
public class PlatformSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain platformSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("platformJwtDecoder") JwtDecoder platformJwtDecoder,
            PlatformJwtAuthenticationConverter platformAuthenticationConverter,
            CorsConfigurationSource wmsCorsConfigurationSource) throws Exception {

        http
                .securityMatcher("/api/platform/v1/**")
                .cors(cors -> cors.configurationSource(wmsCorsConfigurationSource))
                .headers(headers -> headers
                        .crossOriginResourcePolicy(corp -> corp.policy(
                                org.springframework.security.web.header.writers
                                        .CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN)))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/platform/**"))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint()))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .jwt(jwt -> jwt
                                .decoder(platformJwtDecoder)
                                .jwtAuthenticationConverter(platformAuthenticationConverter)));

        return http.build();
    }
}