package com.zirocraft.zirologistics.wms.security.mfa.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WmsTenantMfaThrottleKeyHasherTest {

    @Test
    void derivesStableOpaqueAndTypeSeparatedDigests() {
        byte[] secret = new byte[32];
        secret[0] = 42;
        var hasher = new WmsTenantMfaThrottleKeyHasher(7, secret);

        var accountA = hasher.hashAccount("user-public-id");
        var accountB = hasher.hashAccount("user-public-id");
        var factor = hasher.hashFactor("user-public-id");
        var source = hasher.hashSource("198.51.100.10");

        assertEquals(accountA, accountB);
        assertEquals(WmsTenantMfaThrottleBucketType.ACCOUNT, accountA.type());
        assertEquals(7, accountA.keyVersion());
        assertEquals(64, accountA.digestHex().length());
        assertNotEquals(accountA.digestHex(), factor.digestHex());
        assertNotEquals(accountA.digestHex(), source.digestHex());
        assertNotEquals(accountA.digestHex(), new WmsTenantMfaThrottleKeyHasher(
                8, secret).hashAccount("user-public-id").digestHex());
    }

    @Test
    void rejectsWeakKeysAndNonCanonicalIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> new WmsTenantMfaThrottleKeyHasher(1, new byte[31]));
        var hasher = new WmsTenantMfaThrottleKeyHasher(1, new byte[32]);
        assertThrows(IllegalArgumentException.class, () -> hasher.hashAccount(" "));
        assertThrows(IllegalArgumentException.class, () -> hasher.hashFactor(" factor "));
        assertThrows(IllegalArgumentException.class, () -> hasher.hashSource(null));
    }
}
