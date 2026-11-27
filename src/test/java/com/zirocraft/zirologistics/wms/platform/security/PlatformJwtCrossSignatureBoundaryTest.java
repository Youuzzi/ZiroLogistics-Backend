package com.zirocraft.zirologistics.wms.platform.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformJwtCrossSignatureBoundaryTest {
    @Test
    void rejectsTenantSignedAccessTokenEvenWhenClaimsMimicPlatformAccess() throws Exception {
        KeyPair platformPair = rsaKeyPair();
        KeyPair tenantPair = rsaKeyPair();

        PlatformJwtProperties properties = new PlatformJwtProperties();
        properties.setIssuer("https://platform.example.test");
        properties.setAudience("ziro-platform");
        properties.setKeyId("platform-key");
        properties.setPublicKeyPem(pem("PUBLIC KEY", platformPair.getPublic().getEncoded()));
        properties.setPrivateKeyPem(pem("PRIVATE KEY", platformPair.getPrivate().getEncoded()));

        PlatformRsaKeyMaterial material = PlatformRsaKeyMaterial.fromPem(
                properties.getPublicKeyPem(), properties.getPrivateKeyPem());
        var decoder = new PlatformJwtConfiguration().platformJwtDecoder(properties, material);

        String tenantToken = token(tenantPair, "tenant-key", "https://tenant.example.test",
                "ziro-wms", "access", true);

        assertThrows(JwtException.class, () -> decoder.decode(tenantToken));
    }

    private static String token(KeyPair pair, String kid, String issuer, String audience,
                                String tokenUse, boolean platformClaims) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(issuer).subject("operator-or-user")
                .audience(List.of(audience)).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("token_use", tokenUse);
        if (platformClaims) claims.claim("cv", 0).claim("mfa", true).claim("mfa_at", now.getEpochSecond());
        else claims.claim("mid", "tenant-membership");
        return encoder(pair, kid).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(kid).build(), claims.build())).getTokenValue();
    }

    private static JwtEncoder encoder(KeyPair pair, String kid) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).keyID(kid).build();
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(key));
        return new NimbusJwtEncoder(source);
    }

    private static KeyPair rsaKeyPair() throws Exception {
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
