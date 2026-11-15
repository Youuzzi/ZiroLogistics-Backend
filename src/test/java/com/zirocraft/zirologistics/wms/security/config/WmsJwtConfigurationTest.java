package com.zirocraft.zirologistics.wms.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WmsJwtConfigurationTest {

    @Test
    void createsDedicatedSelectionAndAccessDecodersAndAnEncoder() throws Exception {
        KeyPair pair = generateKeyPair();
        WmsJwtProperties properties = new WmsJwtProperties();
        properties.setIssuer("https://auth.zirologistics.test");
        properties.setTenantSelectionAudience("zirologistics-wms-selection");
        properties.setAccessAudience("zirologistics-wms-api");
        properties.setKeyId("wms-test-key-1");
        properties.setPublicKeyPem(pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        properties.setPrivateKeyPem(pem("PRIVATE KEY", pair.getPrivate().getEncoded()));

        WmsRsaKeyMaterial material = WmsRsaKeyMaterial.fromPem(
                properties.getPublicKeyPem(), properties.getPrivateKeyPem());
        WmsJwtConfiguration configuration = new WmsJwtConfiguration();

        JwtEncoder encoder = configuration.wmsJwtEncoder(properties, material);
        JwtDecoder selectionDecoder = configuration.wmsTenantSelectionJwtDecoder(properties, material);
        JwtDecoder accessDecoder = configuration.wmsAccessJwtDecoder(properties, material);

        assertThat(encoder).isNotNull();
        assertThat(selectionDecoder).isNotNull();
        assertThat(accessDecoder).isNotNull();
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
