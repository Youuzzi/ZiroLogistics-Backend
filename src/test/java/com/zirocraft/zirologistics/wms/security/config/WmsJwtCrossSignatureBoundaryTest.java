package com.zirocraft.zirologistics.wms.security.config;

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

class WmsJwtCrossSignatureBoundaryTest {
    @Test
    void rejectsPlatformSignedAccessTokenEvenWhenClaimsMimicTenantAccess() throws Exception {
        KeyPair tenantPair = rsaKeyPair();
        KeyPair platformPair = rsaKeyPair();

        WmsJwtProperties properties = new WmsJwtProperties();
        properties.setIssuer("https://tenant.example.test");
        properties.setTenantSelectionAudience("ziro-selection");
        properties.setAccessAudience("ziro-wms");
        properties.setKeyId("tenant-key");
        properties.setPublicKeyPem(pem("PUBLIC KEY", tenantPair.getPublic().getEncoded()));
        properties.setPrivateKeyPem(pem("PRIVATE KEY", tenantPair.getPrivate().getEncoded()));

        WmsRsaKeyMaterial material = WmsRsaKeyMaterial.fromPem(
                properties.getPublicKeyPem(), properties.getPrivateKeyPem());
        var decoder = new WmsJwtConfiguration().wmsAccessJwtDecoder(properties, material);

        String platformToken = token(platformPair, "platform-key", "https://platform.example.test",
                "ziro-platform", "platform_access");

        assertThrows(JwtException.class, () -> decoder.decode(platformToken));
    }

    private static String token(KeyPair pair, String kid, String issuer, String audience, String tokenUse) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("operator-or-user")
                .audience(List.of(audience)).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("token_use", tokenUse).claim("cv", 0).claim("mfa", true)
                .claim("mfa_at", now.getEpochSecond()).build();
        return encoder(pair, kid).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(kid).build(), claims)).getTokenValue();
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
