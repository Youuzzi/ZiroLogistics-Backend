package com.zirocraft.zirologistics.wms.security.config;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WmsRsaKeyMaterialTest {

    @Test
    void acceptsMatchingRsaPemKeyPair() throws Exception {
        KeyPair pair = generateKeyPair(2048);
        WmsRsaKeyMaterial material = WmsRsaKeyMaterial.fromPem(
                pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                pem("PRIVATE KEY", pair.getPrivate().getEncoded()));

        assertThat(material.publicKey()).isNotNull();
        assertThat(material.privateKey()).isNotNull();
    }

    @Test
    void rejectsMismatchedKeyPair() throws Exception {
        KeyPair first = generateKeyPair(2048);
        KeyPair second = generateKeyPair(2048);

        assertThatThrownBy(() -> WmsRsaKeyMaterial.fromPem(
                pem("PUBLIC KEY", first.getPublic().getEncoded()),
                pem("PRIVATE KEY", second.getPrivate().getEncoded())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match");
    }

    private static KeyPair generateKeyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String pem(String label, byte[] encoded) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + label + "-----";
    }
}
