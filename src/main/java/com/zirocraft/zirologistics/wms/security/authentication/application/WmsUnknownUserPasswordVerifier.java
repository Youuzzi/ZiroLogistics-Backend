package com.zirocraft.zirologistics.wms.security.authentication.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Performs password-hash work for an unknown account using a process-local,
 * randomly generated dummy hash.
 *
 * The result of matches is deliberately discarded: this operation must never
 * authenticate an unknown account.
 */
@Component
@Profile("wms")
public final class WmsUnknownUserPasswordVerifier {

    private static final int DUMMY_SECRET_BYTES = 48;

    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public WmsUnknownUserPasswordVerifier(
            @Qualifier("wmsPasswordEncoder") PasswordEncoder passwordEncoder) {
        this(passwordEncoder, new SecureRandom());
    }

    WmsUnknownUserPasswordVerifier(PasswordEncoder passwordEncoder, SecureRandom secureRandom) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        Objects.requireNonNull(secureRandom, "secureRandom must not be null");

        byte[] secret = new byte[DUMMY_SECRET_BYTES];
        secureRandom.nextBytes(secret);
        try {
            String dummyPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            this.dummyPasswordHash = Objects.requireNonNull(
                    passwordEncoder.encode(dummyPassword),
                    "PasswordEncoder returned no dummy hash");
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * Performs one Argon2id verification-equivalent operation for an unknown
     * email. The boolean result is intentionally ignored and never returned.
     */
    public void performDummyVerification(CharSequence submittedPassword) {
        Objects.requireNonNull(submittedPassword, "submittedPassword must not be null");
        passwordEncoder.matches(submittedPassword, dummyPasswordHash);
    }
}
