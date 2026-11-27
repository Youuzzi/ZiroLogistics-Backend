package com.zirocraft.zirologistics.wms.security.mfa.application;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WmsTenantMfaRuntimeConfigurationTest {
    @Test
    void loadsActiveAndRetainedKeysFromBase64Configuration() {
        byte[] oldKey = new byte[32];
        byte[] activeKey = new byte[32];
        java.util.Arrays.fill(oldKey, (byte) 1);
        java.util.Arrays.fill(activeKey, (byte) 2);
        String encoded = "mfa-v1=" + Base64.getEncoder().encodeToString(oldKey)
                + ",mfa-v2=" + Base64.getEncoder().encodeToString(activeKey);

        WmsTenantMfaKeyRing ring = WmsTenantMfaRuntimeConfiguration.createKeyRing("mfa-v2", encoded);

        assertEquals("mfa-v2", ring.activeKeyVersion());
        assertArrayEquals(activeKey, ring.keyFor("mfa-v2"));
        assertArrayEquals(oldKey, ring.keyFor("mfa-v1"));
    }

    @Test
    void failsClosedForMissingActiveKeyOrMalformedMaterial() {
        assertThrows(IllegalStateException.class,
                () -> WmsTenantMfaRuntimeConfiguration.createKeyRing("", "v1="
                        + Base64.getEncoder().encodeToString(new byte[32])));
        assertThrows(IllegalStateException.class,
                () -> WmsTenantMfaRuntimeConfiguration.createKeyRing("v1", ""));
        assertThrows(IllegalStateException.class,
                () -> WmsTenantMfaRuntimeConfiguration.createKeyRing("v1", "v1=not-base64!"));
        assertThrows(IllegalArgumentException.class,
                () -> WmsTenantMfaRuntimeConfiguration.createKeyRing("v1",
                        "v1=" + Base64.getEncoder().encodeToString(new byte[31])));
        assertThrows(IllegalStateException.class,
                () -> WmsTenantMfaRuntimeConfiguration.createKeyRing("v1",
                        "v1=" + Base64.getEncoder().encodeToString(new byte[32])
                                + ",v1=" + Base64.getEncoder().encodeToString(new byte[32])));
    }
}
