package com.zirocraft.zirologistics.wms.identity.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WmsEmailNormalizerTest {

    @Test
    void trimsAndLowercasesEmailUsingRootLocale() {
        assertThat(WmsEmailNormalizer.normalize("  USER.Name+Tag@Example.COM  "))
                .isEqualTo("user.name+tag@example.com");
    }

    @Test
    void rejectsNullBlankOverlongAndControlCharacterInput() {
        assertThatThrownBy(() -> WmsEmailNormalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WmsEmailNormalizer.normalize("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WmsEmailNormalizer.normalize("a".repeat(255)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WmsEmailNormalizer.normalize("user\n@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
