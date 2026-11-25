package com.zirocraft.zirologistics.wms.platform.identity.application;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Performs a dummy password-hash verification when an operator email is unknown. */
public final class PlatformUnknownPasswordVerifier {
    private static final int SECRET_BYTES = 48;

    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public PlatformUnknownPasswordVerifier(PasswordEncoder passwordEncoder) {
        this(passwordEncoder, new SecureRandom());
    }

    PlatformUnknownPasswordVerifier(PasswordEncoder passwordEncoder, SecureRandom random) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        Objects.requireNonNull(random, "random must not be null");
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        try {
            String dummyPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            this.dummyPasswordHash = Objects.requireNonNull(passwordEncoder.encode(dummyPassword));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    public void performDummyVerification(CharSequence submittedPassword) {
        Objects.requireNonNull(submittedPassword, "submittedPassword must not be null");
        passwordEncoder.matches(submittedPassword, dummyPasswordHash);
    }
}
