package com.zirocraft.zirologistics.wms.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WmsJwtConfigurationTest {

    @Test
    void jwtConfigurationIsNotLoadedOutsideWmsProfile() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(WmsJwtConfiguration.class);
            context.refresh();

            assertThat(context.getBeansOfType(JwtEncoder.class)).isEmpty();
            assertThat(context.getBeansOfType(JwtDecoder.class)).isEmpty();
        }
    }

    @Test
    void jwtBeansRemainDisabledInWmsProfileUnlessExplicitlyEnabled() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("wms");
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource(
                            "test", java.util.Map.of("wms.security.jwt.enabled", "false")));
            context.register(WmsJwtConfiguration.class);
            context.refresh();

            assertThat(context.getBeansOfType(JwtEncoder.class)).isEmpty();
            assertThat(context.getBeansOfType(JwtDecoder.class)).isEmpty();
        }
    }

    @Test
    void rejectsAccessTokenFromUnexpectedIssuer() throws Exception {
        KeyPair pair = generateKeyPair();
        WmsJwtProperties properties = properties(pair);
        WmsRsaKeyMaterial material = WmsRsaKeyMaterial.fromPem(
                properties.getPublicKeyPem(), properties.getPrivateKeyPem());
        JwtEncoder encoder = new WmsJwtConfiguration().wmsJwtEncoder(properties, material);
        JwtDecoder accessDecoder = new WmsJwtConfiguration().wmsAccessJwtDecoder(properties, material);

        String token = encodeWithOverrides(encoder, properties, "access",
                properties.getAccessAudience(), "membership-public-id",
                "https://attacker.example", Instant.now().minusSeconds(30),
                Instant.now().plusSeconds(60), properties.getKeyId());

        assertThatThrownBy(() -> accessDecoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredAccessToken() throws Exception {
        KeyPair pair = generateKeyPair();
        WmsJwtProperties properties = properties(pair);
        WmsRsaKeyMaterial material = WmsRsaKeyMaterial.fromPem(
                properties.getPublicKeyPem(), properties.getPrivateKeyPem());
        JwtEncoder encoder = new WmsJwtConfiguration().wmsJwtEncoder(properties, material);
        JwtDecoder accessDecoder = new WmsJwtConfiguration().wmsAccessJwtDecoder(properties, material);

        Instant expiredAt = Instant.now().minusSeconds(30);
        String token = encodeWithOverrides(encoder, properties, "access",
                properties.getAccessAudience(), "membership-public-id",
                properties.getIssuer(), expiredAt.minusSeconds(60), expiredAt, properties.getKeyId());

        assertThatThrownBy(() -> accessDecoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void acceptsOnlyTheMatchingTokenProfileAndAudience() throws Exception {
        KeyPair pair = generateKeyPair();
        WmsJwtProperties properties = properties(pair);
        WmsRsaKeyMaterial material = WmsRsaKeyMaterial.fromPem(
                properties.getPublicKeyPem(), properties.getPrivateKeyPem());
        WmsJwtConfiguration configuration = new WmsJwtConfiguration();

        JwtEncoder encoder = configuration.wmsJwtEncoder(properties, material);
        JwtDecoder selectionDecoder = configuration.wmsTenantSelectionJwtDecoder(properties, material);
        JwtDecoder accessDecoder = configuration.wmsAccessJwtDecoder(properties, material);

        String selectionToken = encode(encoder, properties, "tenant_selection",
                properties.getTenantSelectionAudience(), null);
        String accessToken = encode(encoder, properties, "access",
                properties.getAccessAudience(), "membership-public-id");

        Jwt decodedSelection = selectionDecoder.decode(selectionToken);
        Jwt decodedAccess = accessDecoder.decode(accessToken);

        assertThat(decodedSelection.getSubject()).isEqualTo("user-public-id");
        assertThat(decodedSelection.getClaimAsString("mid")).isNull();
        assertThat(decodedAccess.getClaimAsString("mid")).isEqualTo("membership-public-id");

        assertThatThrownBy(() -> accessDecoder.decode(selectionToken))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> selectionDecoder.decode(accessToken))
                .isInstanceOf(JwtException.class);
    }

    private static String encodeWithOverrides(
            JwtEncoder encoder,
            WmsJwtProperties properties,
            String tokenUse,
            String audience,
            String membershipId,
            String issuer,
            Instant issuedAt,
            Instant expiresAt,
            String keyId) {

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("user-public-id")
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("token_use", tokenUse);
        if (membershipId != null) {
            claims.claim("mid", membershipId);
        }
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keyId)
                .build();
        return encoder.encode(JwtEncoderParameters.from(headers, claims.build())).getTokenValue();
    }

    private static WmsJwtProperties properties(KeyPair pair) {
        WmsJwtProperties properties = new WmsJwtProperties();
        properties.setIssuer("https://auth.zirologistics.test");
        properties.setTenantSelectionAudience("zirologistics-wms-selection");
        properties.setAccessAudience("zirologistics-wms-api");
        properties.setKeyId("wms-test-key-1");
        properties.setPublicKeyPem(pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        properties.setPrivateKeyPem(pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        return properties;
    }

    private static String encode(
            JwtEncoder encoder,
            WmsJwtProperties properties,
            String tokenUse,
            String audience,
            String membershipId) {

        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject("user-public-id")
                .audience(List.of(audience))
                .issuedAt(now)
                .notBefore(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(60))
                .id(UUID.randomUUID().toString())
                .claim("token_use", tokenUse);

        if (membershipId != null) {
            claims.claim("mid", membershipId);
        }

        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(properties.getKeyId())
                .build();
        return encoder.encode(JwtEncoderParameters.from(headers, claims.build())).getTokenValue();
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + label + "-----";
    }
}
