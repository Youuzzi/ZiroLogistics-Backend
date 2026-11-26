package com.zirocraft.zirologistics.wms.security.mfa.application;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WmsTenantTotpServiceTest {
    private static final byte[] ENCRYPTION_KEY = new byte[32];
    private final WmsTenantTotpService service = new WmsTenantTotpService(ENCRYPTION_KEY, "mfa-key-v1");

    @Test
    void matchesRfc6238Sha1SixDigitTestVectors() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals("287082", service.codeAt(secret, Instant.ofEpochSecond(59)));
        assertEquals("081804", service.codeAt(secret, Instant.ofEpochSecond(1_111_111_109L)));
        assertEquals("050471", service.codeAt(secret, Instant.ofEpochSecond(1_111_111_111L)));
        assertEquals("005924", service.codeAt(secret, Instant.ofEpochSecond(1_234_567_890L)));
        assertEquals("279037", service.codeAt(secret, Instant.ofEpochSecond(2_000_000_000L)));
        assertEquals("353130", service.codeAt(secret, Instant.ofEpochSecond(20_000_000_000L)));
    }

    @Test
    void verifiesOnlySixDigitCodesWithinOneStepClockSkew() {
        byte[] secret = service.generateSecret();
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String current = service.codeAt(secret, now);

        assertTrue(service.verify(secret, current, now).isPresent());
        assertTrue(service.verify(secret,
                service.codeAt(secret, now.plusSeconds(30)), now).isPresent());
        assertTrue(service.verify(secret,
                service.codeAt(secret, now.minusSeconds(30)), now).isPresent());
        assertTrue(service.verify(secret, "12x456", now).isEmpty());
        assertTrue(service.verify(secret, "12345", now).isEmpty());
        assertTrue(service.verify(secret, "abcdef", now.plusSeconds(120)).isEmpty());
    }

    @Test
    void encryptsSecretAndBindsCiphertextToUserAndKeyVersion() {
        UUID userId = UUID.randomUUID();
        byte[] secret = service.generateSecret();
        WmsTenantTotpService.EncryptedSecret encrypted = service.encryptSecret(userId, secret);

        assertArrayEquals(secret, service.decryptSecret(userId, encrypted));
        assertThrows(IllegalArgumentException.class,
                () -> service.decryptSecret(UUID.randomUUID(), encrypted));

        WmsTenantTotpService anotherKeyVersion = new WmsTenantTotpService(ENCRYPTION_KEY, "mfa-key-v2");
        assertThrows(IllegalArgumentException.class,
                () -> anotherKeyVersion.decryptSecret(userId, encrypted));
    }

    @Test
    void encryptionPayloadDefensivelyCopiesBinaryValues() {
        UUID userId = UUID.randomUUID();
        byte[] secret = service.generateSecret();
        WmsTenantTotpService.EncryptedSecret encrypted = service.encryptSecret(userId, secret);
        byte[] exposedNonce = encrypted.nonce();
        byte[] exposedCiphertext = encrypted.ciphertext();
        Arrays.fill(exposedNonce, (byte) 0);
        Arrays.fill(exposedCiphertext, (byte) 0);

        assertArrayEquals(secret, service.decryptSecret(userId, encrypted));
    }

    @Test
    void generatesValidBase32SecretAndProvisioningUri() {
        byte[] secret = service.generateSecret();
        String encoded = service.encodeBase32(secret);
        assertEquals(32, encoded.length());
        assertTrue(encoded.matches("[A-Z2-7]+"));

        String uri = service.provisioningUri(secret, "operator@example.test", "Ziro Logistics");
        assertTrue(uri.startsWith("otpauth://totp/Ziro%20Logistics:operator%40example.test?"));
        assertTrue(uri.contains("&algorithm=SHA1&digits=6&period=30"));
        assertFalse(encoded.contains("="));
    }

    @Test
    void rejectsWeakKeysAndInvalidSecrets() {
        assertThrows(IllegalArgumentException.class,
                () -> new WmsTenantTotpService(new byte[31], "v1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.codeAt(new byte[10], Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> service.encodeBase32(new byte[10]));
        assertThrows(IllegalArgumentException.class,
                () -> service.provisioningUri(service.generateSecret(), " ", "Ziro"));
    }
}
