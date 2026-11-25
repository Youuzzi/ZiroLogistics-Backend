package com.zirocraft.zirologistics.wms.platform.security;

import org.junit.jupiter.api.Test;
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

class PlatformJwtConfigurationTest {
    @Test
    void acceptsOnlyPlatformAudiencePurposeAndMfaVerifiedTokens() throws Exception {
        PlatformJwtProperties properties = properties(generateKeyPair());
        PlatformRsaKeyMaterial material = PlatformRsaKeyMaterial.fromPem(
                properties.getPublicKeyPem(), properties.getPrivateKeyPem());
        PlatformJwtConfiguration configuration = new PlatformJwtConfiguration();
        JwtEncoder encoder = configuration.platformJwtEncoder(properties, material);
        JwtDecoder decoder = configuration.platformJwtDecoder(properties, material);

        String valid = token(encoder, properties, "platform_access", properties.getAudience(),
                "operator-public-id", 4L, true, Instant.now().minusSeconds(30), properties.getKeyId());
        Jwt decoded = decoder.decode(valid);
        assertThat(decoded.getSubject()).isEqualTo("operator-public-id");
        assertThat(((Number) decoded.getClaims().get("cv")).longValue()).isEqualTo(4L);

        String tenantToken = token(encoder, properties, "access", "zirologistics-wms-api",
                "user-public-id", 4L, true, Instant.now().minusSeconds(30), properties.getKeyId());
        String noMfaToken = token(encoder, properties, "platform_access", properties.getAudience(),
                "operator-public-id", 4L, false, Instant.now().minusSeconds(30), properties.getKeyId());

        assertThatThrownBy(() -> decoder.decode(tenantToken)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(noMfaToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsInvalidCredentialVersionAndMissingMfaTimestamp() throws Exception {
        PlatformJwtProperties properties = properties(generateKeyPair());
        PlatformRsaKeyMaterial material = PlatformRsaKeyMaterial.fromPem(
                properties.getPublicKeyPem(), properties.getPrivateKeyPem());
        PlatformJwtConfiguration configuration = new PlatformJwtConfiguration();
        JwtEncoder encoder = configuration.platformJwtEncoder(properties, material);
        JwtDecoder decoder = configuration.platformJwtDecoder(properties, material);

        String invalidVersion = token(encoder, properties, "platform_access", properties.getAudience(),
                "operator-public-id", -1L, true, Instant.now().minusSeconds(30), properties.getKeyId());
        String missingMfaAt = tokenWithoutMfaAt(encoder, properties);
        String futureMfaAt = token(encoder, properties, "platform_access", properties.getAudience(),
                "operator-public-id", 1L, true, Instant.now().plusSeconds(30), properties.getKeyId());

        assertThatThrownBy(() -> decoder.decode(invalidVersion)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(missingMfaAt)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(futureMfaAt)).isInstanceOf(JwtException.class);
    }

    private static PlatformJwtProperties properties(KeyPair pair) {
        PlatformJwtProperties p = new PlatformJwtProperties();
        p.setIssuer("https://control.zirologistics.test");
        p.setAudience("zirologistics-platform-api");
        p.setKeyId("platform-test-key");
        p.setPublicKeyPem(pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        p.setPrivateKeyPem(pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        return p;
    }

    private static String token(JwtEncoder encoder, PlatformJwtProperties p, String purpose, String audience,
            String subject, long cv, boolean mfa, Instant mfaAt, String keyId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(p.getIssuer()).subject(subject)
                .audience(List.of(audience)).issuedAt(now).notBefore(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(90)).id(UUID.randomUUID().toString())
                .claim("token_use", purpose).claim("cv", cv).claim("mfa", mfa).claim("mfa_at", mfaAt).build();
        return encode(encoder, claims, keyId);
    }

    private static String tokenWithoutMfaAt(JwtEncoder encoder, PlatformJwtProperties p) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(p.getIssuer()).subject("operator-public-id")
                .audience(List.of(p.getAudience())).issuedAt(now).notBefore(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(90)).id(UUID.randomUUID().toString())
                .claim("token_use", "platform_access").claim("cv", 1L).claim("mfa", true).build();
        return encode(encoder, claims, p.getKeyId());
    }

    private static String encode(JwtEncoder encoder, JwtClaimsSet claims, String keyId) {
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(String label, byte[] bytes) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + label + "-----";
    }
}
