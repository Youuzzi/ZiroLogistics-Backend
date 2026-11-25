package com.zirocraft.zirologistics.wms.platform.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("wms")
@ConditionalOnProperty(prefix = "wms.platform.security.jwt", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PlatformJwtProperties.class)
public class PlatformJwtConfiguration {
    @Bean
    PlatformRsaKeyMaterial platformRsaKeyMaterial(PlatformJwtProperties properties) {
        return PlatformRsaKeyMaterial.fromPem(properties.getPublicKeyPem(), properties.getPrivateKeyPem());
    }

    @Bean
    JwtEncoder platformJwtEncoder(PlatformJwtProperties properties, PlatformRsaKeyMaterial material) {
        RSAKey key = new RSAKey.Builder(material.publicKey())
                .privateKey(material.privateKey()).keyID(properties.getKeyId()).build();
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(key));
        return new NimbusJwtEncoder(source);
    }

    @Bean
    JwtDecoder platformJwtDecoder(PlatformJwtProperties properties, PlatformRsaKeyMaterial material) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(material.publicKey())
                .signatureAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> issuerAndTime = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        OAuth2TokenValidator<Jwt> audience = jwt ->
                jwt.getAudience() != null && jwt.getAudience().contains(properties.getAudience())
                        ? OAuth2TokenValidatorResult.success() : failure("Invalid platform token audience");
        OAuth2TokenValidator<Jwt> purpose = jwt ->
                "platform_access".equals(jwt.getClaimAsString("token_use"))
                        ? OAuth2TokenValidatorResult.success() : failure("Invalid platform token purpose");
        OAuth2TokenValidator<Jwt> keyId = jwt ->
                properties.getKeyId().equals(jwt.getHeaders().get("kid"))
                        ? OAuth2TokenValidatorResult.success() : failure("Unknown platform signing key");
        OAuth2TokenValidator<Jwt> operatorSubject = jwt ->
                jwt.getSubject() != null && !jwt.getSubject().isBlank()
                        ? OAuth2TokenValidatorResult.success() : failure("Missing platform operator subject");
        OAuth2TokenValidator<Jwt> credentialVersion = jwt -> {
            Object value = jwt.getClaims().get("cv");
            boolean valid = value instanceof Number number && number.longValue() >= 0
                    && number.doubleValue() == number.longValue();
            return valid ? OAuth2TokenValidatorResult.success() : failure("Invalid platform credential version");
        };
        OAuth2TokenValidator<Jwt> mfa = jwt -> {
            Object verified = jwt.getClaims().get("mfa");
            Object authenticatedAt = jwt.getClaims().get("mfa_at");
            return Boolean.TRUE.equals(verified) && authenticatedAt instanceof java.time.Instant
                    ? OAuth2TokenValidatorResult.success() : failure("Platform MFA authentication is required");
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerAndTime, audience, purpose, keyId, operatorSubject, credentialVersion, mfa));
        return decoder;
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", description, null));
    }
}
