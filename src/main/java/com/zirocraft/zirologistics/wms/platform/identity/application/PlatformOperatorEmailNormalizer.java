package com.zirocraft.zirologistics.wms.platform.identity.application;

import java.util.Locale;

/** Canonical email identifier policy for the SaaS control-plane operator directory. */
public final class PlatformOperatorEmailNormalizer {
    private PlatformOperatorEmailNormalizer() {
    }

    public static String normalize(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Email must not be null");
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        if (normalized.length() > 254 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Email is not a valid platform operator identifier");
        }
        return normalized;
    }
}
