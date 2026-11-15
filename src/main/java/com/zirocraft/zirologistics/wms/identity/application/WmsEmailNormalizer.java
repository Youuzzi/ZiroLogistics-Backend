package com.zirocraft.zirologistics.wms.identity.application;

import java.util.Locale;

/**
 * Canonicalizes email input for WMS identity lookup.
 *
 * This is an application lookup policy, not a claim that RFC email local-parts
 * are universally case-insensitive. WMS treats email identifiers as
 * case-insensitive and removes surrounding whitespace before persistence or
 * lookup. Syntax validation belongs at the API boundary.
 */
public final class WmsEmailNormalizer {

    private WmsEmailNormalizer() {
    }

    public static String normalize(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Email must not be null");
        }

        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        if (normalized.length() > 254) {
            throw new IllegalArgumentException("Email must not exceed 254 characters");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Email must not contain control characters");
        }

        return normalized;
    }
}
