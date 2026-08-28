package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.config.EncryptionProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionServiceTest {

    private static final String BASE64_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String HEX_KEY = "a".repeat(64);
    private static final String HEX_KEY_UPPER = "A".repeat(64);

    @Test
    void disabledWhenNoKey() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(null));
        assertThat(svc.isEnabled()).isFalse();
        assertThat(svc.encrypt("hello")).isEqualTo("hello");
        assertThat(svc.decrypt("hello")).isEqualTo("hello");
    }

    @Test
    void disabledWhenBlankKey() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties("   "));
        assertThat(svc.isEnabled()).isFalse();
    }

    @Test
    void disabledWhenNullProps() {
        EncryptionService svc = new EncryptionService(null);
        assertThat(svc.isEnabled()).isFalse();
    }

    @Test
    void encryptDecryptRoundTripWithBase64Key() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(BASE64_KEY));
        assertThat(svc.isEnabled()).isTrue();
        String encrypted = svc.encrypt("mysecret123");
        assertThat(encrypted).startsWith("ENC:v1:");
        assertThat(encrypted).isNotEqualTo("mysecret123");
        assertThat(svc.decrypt(encrypted)).isEqualTo("mysecret123");
    }

    @Test
    void encryptDecryptRoundTripWithHexKey() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(HEX_KEY));
        assertThat(svc.isEnabled()).isTrue();
        String encrypted = svc.encrypt("hello world");
        assertThat(svc.decrypt(encrypted)).isEqualTo("hello world");
    }

    @Test
    void hexKeyCaseInsensitive() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(HEX_KEY_UPPER));
        assertThat(svc.isEnabled()).isTrue();
        String encrypted = svc.encrypt("test");
        assertThat(svc.decrypt(encrypted)).isEqualTo("test");
    }

    @Test
    void encryptProducesDifferentCiphertextEachTimeDueToRandomIv() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(BASE64_KEY));
        String c1 = svc.encrypt("same");
        String c2 = svc.encrypt("same");
        assertThat(c1).isNotEqualTo(c2);
        assertThat(svc.decrypt(c1)).isEqualTo("same");
        assertThat(svc.decrypt(c2)).isEqualTo("same");
    }

    @Test
    void encryptDecryptHandlesNull() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(BASE64_KEY));
        assertThat(svc.encrypt(null)).isNull();
        assertThat(svc.decrypt(null)).isNull();
    }

    @Test
    void decryptPlaintextPassthroughWhenEnabled() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(BASE64_KEY));
        assertThat(svc.decrypt("plaintext")).isEqualTo("plaintext");
        assertThat(svc.decrypt("not-encrypted")).isEqualTo("not-encrypted");
    }

    @Test
    void decryptEncryptedWithoutKeyThrows() {
        EncryptionService enabled = new EncryptionService(new EncryptionProperties(BASE64_KEY));
        String encrypted = enabled.encrypt("secret");
        EncryptionService disabled = new EncryptionService(new EncryptionProperties(null));
        assertThatThrownBy(() -> disabled.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENCRYPTION_KEY not configured");
    }

    @Test
    void encryptDecryptHandlesEmptyString() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(BASE64_KEY));
        String encrypted = svc.encrypt("");
        assertThat(svc.decrypt(encrypted)).isEqualTo("");
    }

    @Test
    void encryptDecryptHandlesSpecialCharsAndUnicode() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(BASE64_KEY));
        String original = "p@ss#word/ café \u2603";
        String encrypted = svc.encrypt(original);
        assertThat(svc.decrypt(encrypted)).isEqualTo(original);
    }

    @Test
    void rejectsInvalidBase64Key() {
        assertThatThrownBy(() -> new EncryptionService(new EncryptionProperties("not-valid-base64!!!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENCRYPTION_KEY");
    }

    @Test
    void rejectsWrongLengthBase64Key() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new EncryptionService(new EncryptionProperties(shortKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejectsTooShortEncryptedPayload() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(BASE64_KEY));
        String tooShort = "ENC:v1:" + Base64.getEncoder().encodeToString(new byte[5]);
        assertThatThrownBy(() -> svc.decrypt(tooShort))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTamperedCiphertext() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties(BASE64_KEY));
        String encrypted = svc.encrypt("hello");
        // Flip last char to corrupt tag
        String tampered = encrypted.substring(0, encrypted.length() - 1)
                + (encrypted.charAt(encrypted.length() - 1) == 'A' ? 'B' : 'A');
        assertThatThrownBy(() -> svc.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void trimsWhitespaceFromKey() {
        EncryptionService svc = new EncryptionService(new EncryptionProperties("  " + BASE64_KEY + "  "));
        assertThat(svc.isEnabled()).isTrue();
        assertThat(svc.decrypt(svc.encrypt("test"))).isEqualTo("test");
    }
}
