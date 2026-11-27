package com.zirocraft.zirologistics.wms.platform.security;

import com.zirocraft.zirologistics.wms.security.config.WmsJwtProperties;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformTenantJwtTrustDomainGuardTest {
    @Test
    void acceptsDistinctKeysAndTrustIdentifiers() throws Exception {
        assertThatCode(() -> PlatformTenantJwtTrustDomainGuard.validate(
                platformProperties(generateKeyPair()), tenantProperties(generateKeyPair())))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsReusedRsaPublicKey() throws Exception {
        KeyPair shared = generateKeyPair();
        assertThatThrownBy(() -> PlatformTenantJwtTrustDomainGuard.validate(
                platformProperties(shared), tenantProperties(shared)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing keys must be different");
    }

    @Test
    void rejectsReusedIssuer() throws Exception {
        PlatformJwtProperties platform = platformProperties(generateKeyPair());
        WmsJwtProperties tenant = tenantProperties(generateKeyPair());
        tenant.setIssuer(platform.getIssuer());
        assertThatThrownBy(() -> PlatformTenantJwtTrustDomainGuard.validate(platform, tenant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuers must be different");
    }

    @Test
    void rejectsReusedKeyIdentifier() throws Exception {
        PlatformJwtProperties platform = platformProperties(generateKeyPair());
        WmsJwtProperties tenant = tenantProperties(generateKeyPair());
        tenant.setKeyId(platform.getKeyId());
        assertThatThrownBy(() -> PlatformTenantJwtTrustDomainGuard.validate(platform, tenant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key identifiers must be different");
    }

    @Test
    void rejectsAudienceCollisionWithEitherTenantTokenProfile() throws Exception {
        PlatformJwtProperties platform = platformProperties(generateKeyPair());
        WmsJwtProperties tenant = tenantProperties(generateKeyPair());
        tenant.setAccessAudience(platform.getAudience());
        assertThatThrownBy(() -> PlatformTenantJwtTrustDomainGuard.validate(platform, tenant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audiences must be different");
    }

    private static PlatformJwtProperties platformProperties(KeyPair pair) {
        PlatformJwtProperties p = new PlatformJwtProperties();
        p.setIssuer("https://control.example.test");
        p.setAudience("control-plane-api");
        p.setKeyId("platform-signing-key");
        p.setPublicKeyPem(pem(pair.getPublic().getEncoded()));
        p.setPrivateKeyPem("unused-by-this-guard");
        return p;
    }

    private static WmsJwtProperties tenantProperties(KeyPair pair) {
        WmsJwtProperties p = new WmsJwtProperties();
        p.setIssuer("https://tenant-auth.example.test");
        p.setTenantSelectionAudience("tenant-selection-api");
        p.setAccessAudience("tenant-wms-api");
        p.setKeyId("tenant-signing-key");
        p.setPublicKeyPem(pem(pair.getPublic().getEncoded()));
        p.setPrivateKeyPem("unused-by-this-guard");
        return p;
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(byte[] encoded) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----";
    }
}
