package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.identity.domain.WmsGeneratedIdentityActionToken;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WmsIdentityActionTokenGeneratorTest {

    private final WmsIdentityActionTokenGenerator generator = new WmsIdentityActionTokenGenerator();

    @Test
    void generates256BitUrlSafeUnpaddedTokenAndSha256Digest() throws Exception {
        WmsGeneratedIdentityActionToken issued = generator.generate();

        assertThat(issued.rawToken()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        byte[] decodedSecret = Base64.getUrlDecoder().decode(issued.rawToken());
        assertThat(decodedSecret).hasSize(32);
        assertThat(issued.digest()).hasSize(32);
        assertThat(issued.digest()).containsExactly(MessageDigest.getInstance("SHA-256").digest(decodedSecret));
        assertThat(issued.digest()).isNotEqualTo(decodedSecret);
    }

    @Test
    void presentedTokenProducesTheSameDigestAsTheIssuedToken() {
        WmsGeneratedIdentityActionToken issued = generator.generate();

        assertThat(generator.digestPresentedToken(issued.rawToken()))
                .containsExactly(issued.digest());
    }

    @Test
    void separateCallsProduceIndependentSecretsAndDigests() {
        WmsGeneratedIdentityActionToken first = generator.generate();
        WmsGeneratedIdentityActionToken second = generator.generate();

        assertThat(second.rawToken()).isNotEqualTo(first.rawToken());
        assertThat(second.digest()).isNotEqualTo(first.digest());
    }

    @Test
    void digestAccessorReturnsADefensiveCopy() {
        WmsGeneratedIdentityActionToken issued = generator.generate();
        byte[] firstRead = issued.digest();
        Arrays.fill(firstRead, (byte) 0);

        assertThat(issued.digest()).isNotEqualTo(firstRead);
        assertThat(generator.digestPresentedToken(issued.rawToken()))
                .containsExactly(issued.digest());
    }

    @Test
    void rejectsNullMalformedPaddedAndNonCanonicalTokens() {
        assertThatThrownBy(() -> generator.digestPresentedToken(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.digestPresentedToken(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.digestPresentedToken("not-a-token"))
                .isInstanceOf(IllegalArgumentException.class);

        String padded = generator.generate().rawToken() + "=";
        assertThatThrownBy(() -> generator.digestPresentedToken(padded))
                .isInstanceOf(IllegalArgumentException.class);

        String invalidAlphabet = "!" + generator.generate().rawToken().substring(1);
        assertThatThrownBy(() -> generator.digestPresentedToken(invalidAlphabet))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
