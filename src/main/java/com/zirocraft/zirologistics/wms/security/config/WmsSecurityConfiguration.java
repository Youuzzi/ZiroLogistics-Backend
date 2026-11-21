package com.zirocraft.zirologistics.wms.security.config;

import com.zirocraft.zirologistics.wms.security.application.WmsJwtAuthenticationConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Isolated HTTP security boundary for the WMS servlet runtime.
 *
 * No WMS HTTP endpoint is public by default. Until JWT is explicitly enabled,
 * the runtime denies all requests instead of silently falling back to an open chain.
 * Non-web WMS processes deliberately do not create an HTTP security chain.
 */
@Configuration(proxyBeanMethods = false)
@Profile("wms")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WmsSecurityConfiguration {

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "wms.security.jwt", name = "enabled", havingValue = "true")
    SecurityFilterChain wmsAuthenticatedSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("wmsAccessJwtDecoder") JwtDecoder accessJwtDecoder,
            WmsJwtAuthenticationConverter authenticationConverter,
            CorsConfigurationSource wmsCorsConfigurationSource,
            @Value("${wms.security.http.auth-endpoints-enabled:false}") boolean authEndpointsEnabled) throws Exception {

        http
                .securityMatcher("/**")
                .cors(cors -> cors.configurationSource(wmsCorsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    if (authEndpointsEnabled) {
                        authorize.requestMatchers(HttpMethod.POST,
                                "/api/wms/v1/auth/credentials",
                                "/api/wms/v1/auth/tenant-selection",
                                "/api/wms/v1/auth/password-reset-requests",
                                "/api/wms/v1/auth/password-resets",
                                "/api/wms/v1/auth/email-verification-requests",
                                "/api/wms/v1/auth/email-verifications",
                                "/api/wms/v1/invitations/acceptances"
                        ).permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .jwt(jwt -> jwt
                                .decoder(accessJwtDecoder)
                                .jwtAuthenticationConverter(authenticationConverter)));

        return http.build();
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(
            prefix = "wms.security.jwt",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    SecurityFilterChain wmsJwtDisabledSecurityFilterChain(
            HttpSecurity http, CorsConfigurationSource wmsCorsConfigurationSource) throws Exception {
        http
                .securityMatcher("/**")
                .cors(cors -> cors.configurationSource(wmsCorsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll());

        return http.build();
    }
}
