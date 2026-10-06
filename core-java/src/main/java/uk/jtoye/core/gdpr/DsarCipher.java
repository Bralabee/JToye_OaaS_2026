package uk.jtoye.core.gdpr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one authenticated-encryption utility for DSAR data at rest (D-19, owner ruling 2026-10-05).
 *
 * <h2>Why an address is stored at all</h2>
 *
 * V62 kept the subject only as a one-way digest, and said a readable address must never be stored.
 * D-19 amends that, narrowly. The worker has to reach the subject: D-01 emails an export link, D-03
 * looks the subject up in Keycloak, #777 expects a completion email, and an Article 15 request that
 * matches nothing still needs a "we hold nothing" reply. None of those can be done from a digest. So
 * the address is kept ENCRYPTED, with the key outside the database, and only until the request
 * reaches a terminal state; V70 records the full justification and enforces the terminal-state rule
 * as a CHECK constraint.
 *
 * <h2>The construction</h2>
 *
 * AES-256-GCM from the JDK ({@code javax.crypto}); no third-party library. Each value gets a fresh
 * random 12-byte IV and a 128-bit tag, and the stored form is {@code IV || ciphertext-with-tag} in a
 * single BYTEA. The associated data is {@code "<purpose>:<request id>"}, so a ciphertext copied onto
 * another row, or offered for another purpose, fails the tag check instead of decrypting. GCM was
 * chosen over a bare mode for exactly that property: confidentiality alone would let a row's
 * ciphertext be moved and still decrypt.
 *
 * <h2>Failing fast, and never leaking the key</h2>
 *
 * The key comes only from {@code jtoye.gdpr.dsar.encryption-key} (env {@code DSAR_ENCRYPTION_KEY}),
 * 64 hexadecimal characters. It is validated here, at bean construction, so the application refuses
 * to start without a valid one rather than accepting requests it could not later act on. The refusal
 * names the property and how to generate a key, and never the value: an exception message ends up in
 * logs, and a partially-correct key in a log is a partially-leaked key.
 *
 * <p>This class does no logging at all, deliberately, and its exceptions carry no plaintext.
 *
 * <h2>Key rotation</h2>
 *
 * There is no rotation mechanism, by decision (threat T-31.1-25, accepted). The data is transient:
 * it exists from intake until a terminal state, bounded by the verification TTL plus processing. A
 * rotation is therefore "drain the queue, then swap the key".
 */
@Component
public class DsarCipher {

    /** What a ciphertext is for. Part of the associated data, so purposes cannot be swapped. */
    public enum Purpose {
        /** The verified subject address on {@code dsar_request} (V70). */
        SUBJECT_ADDRESS,
        /** The assembled Article 15 export document on {@code dsar_access_export} (V75, 31.1-16). */
        ACCESS_EXPORT
    }

    static final String PROPERTY = "jtoye.gdpr.dsar.encryption-key";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final Pattern HEX_64 = Pattern.compile("[0-9a-fA-F]{64}");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /**
     * @param hexKey 64 hexadecimal characters (32 bytes). The empty inline default exists so that an
     *               absent property reaches THIS refusal, with its generation hint, rather than a
     *               generic placeholder error.
     * @throws IllegalStateException when the key is absent, blank, the wrong length or not hex;
     *                               the message names the property and never the value
     */
    public DsarCipher(@Value("${" + PROPERTY + ":}") String hexKey) {
        if (hexKey == null || !HEX_64.matcher(hexKey).matches()) {
            throw new IllegalStateException(PROPERTY + " must be 64 hexadecimal characters (32 bytes); "
                    + "generate one with: openssl rand -hex 32");
        }
        byte[] raw = HexFormat.of().parseHex(hexKey);
        try {
            this.key = new SecretKeySpec(raw, "AES");
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /**
     * Encrypt for storage.
     *
     * @return {@code IV || ciphertext-with-tag}, ready for one BYTEA column
     */
    public byte[] encrypt(Purpose purpose, UUID requestId, String plaintext) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(plaintext, "plaintext");

        byte[] iv = new byte[IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(purpose.name(), requestId));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] stored = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, stored, 0, IV_BYTES);
            System.arraycopy(sealed, 0, stored, IV_BYTES, sealed.length);
            return stored;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("DSAR encryption failed", e);
        }
    }

    /**
     * Decrypt a value written by {@link #encrypt} for the same purpose and request id.
     *
     * @throws IllegalStateException when the value fails authentication (tampered, or bound to a
     *                               different row or purpose) or is malformed
     */
    public String decrypt(Purpose purpose, UUID requestId, byte[] stored) {
        Objects.requireNonNull(purpose, "purpose");
        return decryptWithLabel(purpose.name(), requestId, stored);
    }

    /**
     * The decrypt path with the associated-data label supplied directly. Package-private so tests
     * can prove the purpose binding with labels of their choosing (31.1-07, written while only one
     * {@link Purpose} existed; 31.1-16 reads an ACCESS_EXPORT payload through it by its label);
     * production code goes through {@link #decrypt}.
     */
    String decryptWithLabel(String label, UUID requestId, byte[] stored) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(stored, "stored");
        if (stored.length < IV_BYTES + TAG_BYTES) {
            throw new IllegalStateException("DSAR ciphertext is malformed");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            cipher.updateAAD(associatedData(label, requestId));
            byte[] plain = cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("DSAR ciphertext failed authentication", e);
        }
    }

    private static byte[] associatedData(String label, UUID requestId) {
        return (label + ":" + requestId).getBytes(StandardCharsets.UTF_8);
    }
}
