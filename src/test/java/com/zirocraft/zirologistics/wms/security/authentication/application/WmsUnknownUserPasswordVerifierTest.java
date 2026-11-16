package com.zirocraft.zirologistics.wms.security.authentication.application;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WmsUnknownUserPasswordVerifierTest {

    @Test
    void createsOneDummyHashAndUsesItForEachUnknownUserAttempt() {
        RecordingPasswordEncoder encoder = new RecordingPasswordEncoder();
        WmsUnknownUserPasswordVerifier verifier = new WmsUnknownUserPasswordVerifier(
                encoder, new java.security.SecureRandom());

        assertEquals(1, encoder.encodeCalls);
        assertNotNull(encoder.encodedValue);
        assertFalse(encoder.encodedValue.isBlank());

        verifier.performDummyVerification("first-submitted-password");
        verifier.performDummyVerification("second-submitted-password");

        assertEquals(2, encoder.matchCalls);
        assertEquals(List.of("first-submitted-password", "second-submitted-password"), encoder.rawPasswords);
        assertEquals(List.of(encoder.encodedValue, encoder.encodedValue), encoder.encodedHashes);
    }

    @Test
    void dummyMatchResultCannotBeUsedAsAuthenticationResult() {
        RecordingPasswordEncoder encoder = new RecordingPasswordEncoder();
        encoder.matchResult = true;
        WmsUnknownUserPasswordVerifier verifier = new WmsUnknownUserPasswordVerifier(
                encoder, new java.security.SecureRandom());

        assertDoesNotThrow(() -> verifier.performDummyVerification("any-password"));
        assertEquals(1, encoder.matchCalls);
    }

    @Test
    void rejectsNullSubmittedPassword() {
        WmsUnknownUserPasswordVerifier verifier = new WmsUnknownUserPasswordVerifier(
                new RecordingPasswordEncoder(), new java.security.SecureRandom());

        assertThrows(NullPointerException.class, () -> verifier.performDummyVerification(null));
    }

    private static final class RecordingPasswordEncoder implements PasswordEncoder {
        private int encodeCalls;
        private int matchCalls;
        private String encodedValue;
        private boolean matchResult;
        private final List<String> rawPasswords = new ArrayList<>();
        private final List<String> encodedHashes = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            encodeCalls++;
            encodedValue = "dummy-encoded-hash";
            return encodedValue;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchCalls++;
            rawPasswords.add(rawPassword.toString());
            encodedHashes.add(encodedPassword);
            return matchResult;
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return false;
        }
    }
}
