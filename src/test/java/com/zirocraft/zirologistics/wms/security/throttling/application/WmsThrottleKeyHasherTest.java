package com.zirocraft.zirologistics.wms.security.throttling.application;

import com.zirocraft.zirologistics.wms.security.throttling.domain.WmsThrottleBucketType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WmsThrottleKeyHasherTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes();

    private final WmsThrottleKeyHasher hasher = new WmsThrottleKeyHasher(3, SECRET);

    @Test
    void normalizedEmailVariantsProduceTheSameOpaqueAccountKey() {
        assertThat(hasher.hashAccountEmail("  Operator@Example.COM "))
                .isEqualTo(hasher.hashAccountEmail("operator@example.com"));
    }

    @Test
    void accountAndSourceBucketsAreDomainSeparated() {
        assertThat(hasher.hashAccountEmail("192.0.2.10").digestHex())
                .isNotEqualTo(hasher.hashSourceAddress("192.0.2.10").digestHex());
        assertThat(hasher.hashAccountEmail("operator@example.com").type())
                .isEqualTo(WmsThrottleBucketType.ACCOUNT);
        assertThat(hasher.hashSourceAddress("192.0.2.10").type())
                .isEqualTo(WmsThrottleBucketType.SOURCE);
    }

    @Test
    void equivalentIpv6RepresentationsProduceTheSameSourceKey() {
        assertThat(hasher.hashSourceAddress("2001:db8::1"))
                .isEqualTo(hasher.hashSourceAddress("2001:0db8:0:0:0:0:0:1"));
    }

    @Test
    void rejectsInvalidEmailAddressAndWeakKeyMaterial() {
        assertThatThrownBy(() -> hasher.hashAccountEmail("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hashSourceAddress("attacker.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hashSourceAddress("192.0.2.999"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WmsThrottleKeyHasher(1, new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyVersionIsPartOfTheBucketIdentity() {
        WmsThrottleKeyHasher rotated = new WmsThrottleKeyHasher(4, SECRET);

        assertThat(hasher.hashAccountEmail("operator@example.com").digestHex())
                .isEqualTo(rotated.hashAccountEmail("operator@example.com").digestHex());
        assertThat(hasher.hashAccountEmail("operator@example.com").keyVersion())
                .isNotEqualTo(rotated.hashAccountEmail("operator@example.com").keyVersion());
    }
}
