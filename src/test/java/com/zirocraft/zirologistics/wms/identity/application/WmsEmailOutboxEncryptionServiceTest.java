package com.zirocraft.zirologistics.wms.identity.application;

import com.zirocraft.zirologistics.wms.identity.domain.WmsEncryptedEmailPayload;
import com.zirocraft.zirologistics.wms.identity.domain.WmsIdentityActionTokenPurpose;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WmsEmailOutboxEncryptionServiceTest {

    private static final byte[] KEY_V1 = key((byte) 1);
    private static final byte[] KEY_V2 = key((byte) 2);
    private static final UUID MESSAGE_ID = UUID.fromString("2c7a4a60-66c0-4d58-92d2-81b7f5b3d920");
    private static final byte[] PLAINTEXT =
            "{\"actionToken\":\"one-time-secret\",\"tenantName\":\"Warehouse A\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void encryptsAndDecryptsPayloadWithAes256GcmAndCanonicalRecipientBinding() {
        WmsEmailOutboxEncryptionService service =
                new WmsEmailOutboxEncryptionService(Map.of(1, KEY_V1), 1);

        WmsEncryptedEmailPayload encrypted = service.encrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.INVITATION,
                " User@Example.Test ", PLAINTEXT);

        assertThat(encrypted.keyVersion()).isEqualTo(1);
        assertThat(encrypted.nonce()).hasSize(12);
        assertThat(encrypted.ciphertext()).hasSize(PLAINTEXT.length + 16);
        assertThat(encrypted.ciphertext()).isNotEqualTo(PLAINTEXT);

        assertThat(service.decrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.INVITATION,
                "user@example.test", encrypted)).containsExactly(PLAINTEXT);
    }

    @Test
    void usesUniqueNoncesForSeparateMessages() {
        WmsEmailOutboxEncryptionService service =
                new WmsEmailOutboxEncryptionService(Map.of(1, KEY_V1), 1);

        WmsEncryptedEmailPayload first = service.encrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.INVITATION,
                "user@example.test", PLAINTEXT);
        WmsEncryptedEmailPayload second = service.encrypt(
                UUID.randomUUID(), WmsIdentityActionTokenPurpose.INVITATION,
                "user@example.test", PLAINTEXT);

        assertThat(second.nonce()).isNotEqualTo(first.nonce());
    }

    @Test
    void bindsCiphertextToMessageIdPurposeRecipientAndKeyVersion() {
        WmsEmailOutboxEncryptionService service =
                new WmsEmailOutboxEncryptionService(Map.of(1, KEY_V1), 1);
        WmsEncryptedEmailPayload encrypted = service.encrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.INVITATION,
                "user@example.test", PLAINTEXT);

        assertThatThrownBy(() -> service.decrypt(
                UUID.randomUUID(), WmsIdentityActionTokenPurpose.INVITATION,
                "user@example.test", encrypted)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.decrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                "user@example.test", encrypted)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.decrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.INVITATION,
                "other@example.test", encrypted)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detectsCiphertextTamperingAndUnknownKeyVersion() {
        WmsEmailOutboxEncryptionService service =
                new WmsEmailOutboxEncryptionService(Map.of(1, KEY_V1), 1);
        WmsEncryptedEmailPayload encrypted = service.encrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                "user@example.test", PLAINTEXT);

        byte[] tamperedCiphertext = encrypted.ciphertext();
        tamperedCiphertext[0] ^= 0x01;
        WmsEncryptedEmailPayload tampered = new WmsEncryptedEmailPayload(
                encrypted.keyVersion(), encrypted.nonce(), tamperedCiphertext);

        assertThatThrownBy(() -> service.decrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                "user@example.test", tampered)).isInstanceOf(IllegalArgumentException.class);

        WmsEncryptedEmailPayload unknownVersion = new WmsEncryptedEmailPayload(
                9, encrypted.nonce(), encrypted.ciphertext());
        assertThatThrownBy(() -> service.decrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.PASSWORD_RESET,
                "user@example.test", unknownVersion)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsOldKeysForDecryptionAfterRotation() {
        WmsEmailOutboxEncryptionService oldService =
                new WmsEmailOutboxEncryptionService(Map.of(1, KEY_V1), 1);
        WmsEncryptedEmailPayload encryptedByOldKey = oldService.encrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION,
                "user@example.test", PLAINTEXT);

        WmsEmailOutboxEncryptionService rotatedService =
                new WmsEmailOutboxEncryptionService(Map.of(1, KEY_V1, 2, KEY_V2), 2);
        WmsEncryptedEmailPayload encryptedByNewKey = rotatedService.encrypt(
                UUID.randomUUID(), WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION,
                "user@example.test", PLAINTEXT);

        assertThat(encryptedByNewKey.keyVersion()).isEqualTo(2);
        assertThat(rotatedService.decrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.EMAIL_VERIFICATION,
                "user@example.test", encryptedByOldKey)).containsExactly(PLAINTEXT);
    }

    @Test
    void rejectsInvalidKeyRingsAndEmptyOrOversizedPayloads() {
        assertThatThrownBy(() -> new WmsEmailOutboxEncryptionService(Map.of(1, new byte[16]), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WmsEmailOutboxEncryptionService(Map.of(1, KEY_V1), 2))
                .isInstanceOf(IllegalArgumentException.class);

        WmsEmailOutboxEncryptionService service =
                new WmsEmailOutboxEncryptionService(Map.of(1, KEY_V1), 1);
        assertThatThrownBy(() -> service.encrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.INVITATION, "user@example.test", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.encrypt(
                MESSAGE_ID, WmsIdentityActionTokenPurpose.INVITATION,
                "user@example.test", new byte[4_081])).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] key(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }
}
