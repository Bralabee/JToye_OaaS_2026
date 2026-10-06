package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-19: the DSAR subject's address is held at rest only as AES-256-GCM ciphertext.
 *
 * <p>Every key in this class is generated per run from {@link SecureRandom}. There is no literal key
 * anywhere in the test tree, by rule: a literal is one copy-paste away from being somebody's
 * production key, and a secret scanner cannot tell a test key from a real one.
 *
 * <p>The authentication cases are the point of choosing GCM over a bare cipher mode. Confidentiality
 * alone would let a row's ciphertext be copied onto another row (or reused for another purpose) and
 * still decrypt; the tag and the associated data are what make that fail.
 */
class DsarCipherTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PROPERTY = "jtoye.gdpr.dsar.encryption-key";

    private static String randomHexKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return HexFormat.of().formatHex(key);
    }

    private final DsarCipher cipher = new DsarCipher(randomHexKey());

    @Test
    void anAddressRoundTripsUnderTheSamePurposeAndRequestId() {
        UUID id = UUID.randomUUID();
        byte[] stored = cipher.encrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, id, "a@b.test");

        assertThat(cipher.decrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, id, stored)).isEqualTo("a@b.test");
    }

    @Test
    void theStoredBytesNeverContainThePlaintext() {
        String address = "plain.text.probe@example.test";
        byte[] plain = address.getBytes(StandardCharsets.UTF_8);
        byte[] stored = cipher.encrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, UUID.randomUUID(), address);

        assertThat(indexOf(stored, plain)).as("ciphertext must not carry the address").isEqualTo(-1);
        // CONTROL: the same search finds the address in a plain UTF-8 encoding of it, so the -1
        // above is about the ciphertext and not about a search that cannot match.
        byte[] control = ("prefix:" + address).getBytes(StandardCharsets.UTF_8);
        assertThat(indexOf(control, plain)).isEqualTo(7);
        // IV (12) + ciphertext (same length as the plaintext under GCM) + tag (16).
        assertThat(stored).hasSize(12 + plain.length + 16);
    }

    @Test
    void twoEncryptionsOfTheSameAddressDiffer() {
        UUID id = UUID.randomUUID();
        byte[] first = cipher.encrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, id, "same@b.test");
        byte[] second = cipher.encrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, id, "same@b.test");

        assertThat(first).as("a fresh IV per encryption").isNotEqualTo(second);
    }

    @Test
    void aSingleFlippedByteFailsTheTagCheck() {
        UUID id = UUID.randomUUID();
        byte[] stored = cipher.encrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, id, "tamper@b.test");
        stored[stored.length / 2] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, id, stored))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void ciphertextBoundToOneRequestIdDoesNotDecryptUnderAnother() {
        byte[] stored = cipher.encrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, UUID.randomUUID(), "aad@b.test");

        assertThatThrownBy(() -> cipher.decrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, UUID.randomUUID(), stored))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void ciphertextBoundToOnePurposeDoesNotDecryptUnderAnother() {
        // The associated data is "<purpose>:<request id>". There is one purpose today, so the
        // binding is exercised through the package-private seam that takes the AAD label directly.
        UUID id = UUID.randomUUID();
        byte[] stored = cipher.encrypt(DsarCipher.Purpose.SUBJECT_ADDRESS, id, "purpose@b.test");

        assertThatThrownBy(() -> cipher.decryptWithLabel("EXPORT_LINK", id, stored))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(AEADBadTagException.class);
        // CONTROL: the same seam with the real label decrypts, so the failure above is the AAD.
        assertThat(cipher.decryptWithLabel(DsarCipher.Purpose.SUBJECT_ADDRESS.name(), id, stored))
                .isEqualTo("purpose@b.test");
    }

    @Test
    void aKeyOneHexCharacterShortIsRefusedWithoutEchoingIt() {
        String shortKey = randomHexKey().substring(0, 63);
        assertRefusedWithoutKeyMaterial(shortKey);
    }

    @Test
    void aNonHexKeyIsRefusedWithoutEchoingIt() {
        String nonHex = "zz" + randomHexKey().substring(2);
        assertRefusedWithoutKeyMaterial(nonHex);
    }

    @Test
    void aBlankOrMissingKeyIsRefused() {
        for (String blank : new String[]{"", "   ", null}) {
            assertThatThrownBy(() -> new DsarCipher(blank))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(PROPERTY);
        }
    }

    private static void assertRefusedWithoutKeyMaterial(String badKey) {
        assertThatThrownBy(() -> new DsarCipher(badKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(badKey);
                    assertThat(e.getMessage()).doesNotContain(badKey.substring(2, 18));
                    assertThat(e.getCause()).as("no cause that might carry the value").isNull();
                });
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
