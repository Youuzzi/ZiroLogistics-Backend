package com.zirocraft.zirologistics.wms.security.config;

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
@ConditionalOnProperty(prefix = "wms.security.jwt", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WmsJwtProperties.class)
public class WmsJwtConfiguration {

    @Bean
    WmsRsaKeyMaterial wmsRsaKeyMaterial(WmsJwtProperties properties) {
        return WmsRsaKeyMaterial.fromPem(properties.getPublicKeyPem(), properties.getPrivateKeyPem());
    }

    @Bean
    JwtEncoder wmsJwtEncoder(WmsJwtProperties properties, WmsRsaKeyMaterial keyMaterial) {
        RSAKey rsaKey = new RSAKey.Builder(keyMaterial.publicKey())
                .privateKey(keyMaterial.privateKey())
                .keyID(properties.getKeyId())
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean("wmsTenantSelectionJwtDecoder")
    JwtDecoder wmsTenantSelectionJwtDecoder(WmsJwtProperties properties, WmsRsaKeyMaterial keyMaterial) {
        return decoder(properties, keyMaterial, properties.getTenantSelectionAudience(), "tenant_selection", false);
    }

    @Bean("wmsAccessJwtDecoder")
    JwtDecoder wmsAccessJwtDecoder(WmsJwtProperties properties, WmsRsaKeyMaterial keyMaterial) {
        return decoder(properties, keyMaterial, properties.getAccessAudience(), "access", true);
    }

    private JwtDecoder decoder(
            WmsJwtProperties properties,
            WmsRsaKeyMaterial keyMaterial,
            String audience,
            String tokenUse,
            boolean membershipRequired) {

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keyMaterial.publicKey())
                .signatureAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();

        OAuth2TokenValidator<Jwt> issuerAndTime =
                JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        OAuth2TokenValidator<Jwt> audienceValidator = jwt ->
                jwt.getAudience() != null && jwt.getAudience().contains(audience)
                        ? OAuth2TokenValidatorResult.success()
                        : failure("Invalid token audience");
        OAuth2TokenValidator<Jwt> tokenUseValidator = jwt ->
                tokenUse.equals(jwt.getClaimAsString("token_use"))
                        ? OAuth2TokenValidatorResult.success()
                        : failure("Invalid token use");
        OAuth2TokenValidator<Jwt> keyIdValidator = jwt ->
                properties.getKeyId().equals(jwt.getHeaders().get("kid"))
                        ? OAuth2TokenValidatorResult.success()
                        : failure("Unknown signing key identifier");
        OAuth2TokenValidator<Jwt> membershipValidator = jwt -> {
            String membershipId = jwt.getClaimAsString("mid");
            boolean valid = membershipRequired
                    ? membershipId != null && !membershipId.isBlank()
                    : membershipId == null;
            return valid ? OAuth2TokenValidatorResult.success()
                    : failure("Invalid membership claim for token profile");
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerAndTime, audienceValidator, tokenUseValidator, keyIdValidator, membershipValidator));
        return decoder;
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", description, null));
    }
}
