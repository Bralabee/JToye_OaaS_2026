package uk.jtoye.core.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #571 — the published integrator test vector, pinned.
 *
 * <p>{@code docs/webhooks.md} gives a vendor a fixed secret, a fixed timestamp, a
 * fixed literal payload and the exact {@code X-JToye-Signature} they must arrive
 * at. That is only useful if it is TRUE, and a documented vector nobody executes
 * rots the moment someone touches the signing. So this class asserts the vector
 * from both ends:
 *
 * <ul>
 *   <li><b>Against the implementation</b> — {@link WebhookSigner#sign} over the
 *       published inputs must produce the published header. Change the signing
 *       and this goes red.</li>
 *   <li><b>Against the document</b> — {@code docs/webhooks.md} must still contain
 *       those exact literals. Edit the doc's vector without editing the code (or
 *       vice versa) and this goes red. Without this half, the doc and the test
 *       could drift apart while both stayed internally consistent.</li>
 * </ul>
 *
 * <p>It also re-derives the signature from the doc's PROSE description of the
 * canonical string ({@code t + "." + rawBody}) using a fresh {@link Mac} rather
 * than {@code WebhookSigner}, which is what a receiver actually does. If the doc
 * described the wrong bytes, that test — not the round-trip one — is the one that
 * catches it, because a round trip through the same code cannot disagree with
 * itself.
 *
 * <p>The secret below is a published example in a public repository. It is not a
 * key, has never signed anything, and must never be used by a real subscription.
 */
class WebhookSignatureVectorTest {

    private final WebhookSigner signer = new WebhookSigner();

    /** Published example secret — deliberately fake, see class javadoc. */
    static final String VECTOR_SECRET = "whsec_example_do_not_use";

    /** Published example signing timestamp (unix seconds). */
    static final long VECTOR_TIMESTAMP = 1750000000L;

    /**
     * The published payload, byte-for-byte. One line, no trailing newline, no
     * whitespace between tokens — exactly the bytes the delivery worker POSTs.
     */
    static final String VECTOR_PAYLOAD =
            "{\"id\":\"2b4d0f9a-1c3e-4f57-8a6b-9d0e1f2a3b4c\","
                    + "\"type\":\"order.ready\","
                    + "\"tenantId\":\"7f6e5d4c-3b2a-4190-8f7e-6d5c4b3a2910\","
                    + "\"occurredAt\":\"2026-01-15T09:30:00Z\","
                    + "\"version\":\"1\","
                    + "\"data\":{\"orderId\":\"5a4b3c2d-1e0f-4998-8877-665544332211\","
                    + "\"tenantId\":\"7f6e5d4c-3b2a-4190-8f7e-6d5c4b3a2910\","
                    + "\"orderNumber\":\"JT-1042\","
                    + "\"previousStatus\":\"PREPARING\","
                    + "\"newStatus\":\"READY\","
                    + "\"timestamp\":\"2026-01-15T09:30:00Z\","
                    + "\"shopId\":\"3e2d1c0b-9a87-4655-8443-2211ffeeddcc\"}}";

    /**
     * The published {@code X-JToye-Signature} header value for the inputs above.
     *
     * <p>Derived by RUNNING {@link WebhookSigner} against those inputs, not by
     * hand — and then confirmed independently with
     * {@code openssl dgst -sha256 -hmac 'whsec_example_do_not_use'} over
     * {@code "1750000000." + payload} (441 bytes), which returns the same hex. If
     * you change this constant, change {@code docs/webhooks.md} in the same commit
     * or {@link #docsWebhooksMd_publishesExactlyThisVector()} will say so.
     */
    static final String VECTOR_SIGNATURE_HEADER =
            "t=1750000000,v1=fb7885061905854ae6d97c5d587515bb4f4670a675572acce5956ecbe0cb2305";

    private static byte[] payloadBytes() {
        return VECTOR_PAYLOAD.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ the vector

    @Test
    void publishedVector_isWhatTheSignerActuallyProduces() {
        assertThat(signer.sign(payloadBytes(), VECTOR_SECRET, VECTOR_TIMESTAMP))
                .as("docs/webhooks.md publishes this exact X-JToye-Signature for these exact inputs")
                .isEqualTo(VECTOR_SIGNATURE_HEADER);
    }

    @Test
    void publishedVector_verifiesThroughThePublicVerifyPath() {
        assertThat(signer.verify(payloadBytes(), VECTOR_SECRET, VECTOR_TIMESTAMP, VECTOR_SIGNATURE_HEADER))
                .as("a receiver following the doc must accept the published signature")
                .isTrue();
    }

    /**
     * Independent re-derivation: the doc says a receiver computes
     * {@code HMAC-SHA256(secret, t + "." + rawBody)} and lowercase-hex encodes it.
     * This does exactly that with a fresh {@link Mac} — no {@code WebhookSigner} —
     * so a doc that described the wrong canonical string would fail HERE while the
     * round-trip test above stayed green.
     */
    @Test
    void docDescribedCanonicalString_reproducesThePublishedSignature() throws GeneralSecurityException {
        String canonical = VECTOR_TIMESTAMP + "." + VECTOR_PAYLOAD;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(VECTOR_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hex = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat("t=" + VECTOR_TIMESTAMP + ",v1=" + hex)
                .as("the canonical string the doc tells a vendor to sign is t + \".\" + rawBody")
                .isEqualTo(VECTOR_SIGNATURE_HEADER);
    }

    // ------------------------------------- the vector must be capable of failing

    @Test
    void oneFlippedPayloadByte_breaksThePublishedSignature() {
        // "READY" -> "READX": a single byte, in the middle of the body.
        String tampered = VECTOR_PAYLOAD.replace("\"newStatus\":\"READY\"", "\"newStatus\":\"READX\"");
        assertThat(tampered)
                .as("the tamper must actually change the payload, or this test proves nothing")
                .isNotEqualTo(VECTOR_PAYLOAD)
                .hasSameSizeAs(VECTOR_PAYLOAD);

        assertThat(signer.sign(tampered.getBytes(StandardCharsets.UTF_8), VECTOR_SECRET, VECTOR_TIMESTAMP))
                .as("one flipped body byte must invalidate the published signature")
                .isNotEqualTo(VECTOR_SIGNATURE_HEADER);
    }

    @Test
    void oneChangedSecretCharacter_breaksThePublishedSignature() {
        String wrongSecret = VECTOR_SECRET.replace("whsec_example", "whsec_exampld");
        assertThat(wrongSecret).isNotEqualTo(VECTOR_SECRET);

        assertThat(signer.sign(payloadBytes(), wrongSecret, VECTOR_TIMESTAMP))
                .as("a one-character-different secret must not reproduce the published signature")
                .isNotEqualTo(VECTOR_SIGNATURE_HEADER);
    }

    @Test
    void oneSecondOfTimestampDrift_breaksThePublishedSignature() {
        assertThat(signer.sign(payloadBytes(), VECTOR_SECRET, VECTOR_TIMESTAMP + 1))
                .as("the timestamp is inside the signed bytes, not merely alongside them")
                .isNotEqualTo(VECTOR_SIGNATURE_HEADER);
    }

    // ------------------------------------------------ the doc must publish THIS

    /**
     * Doc-parity. Without this the doc could be edited to publish a different
     * vector and every assertion above would stay green — the exact "gate green
     * over a dead feature" shape this issue was filed about.
     */
    @Test
    void docsWebhooksMd_publishesExactlyThisVector() throws IOException {
        Path doc = repoRoot().resolve("docs/webhooks.md");
        assertThat(doc).as("the integrator doc this vector belongs to").exists();

        String text = Files.readString(doc, StandardCharsets.UTF_8);
        assertThat(text)
                .as("docs/webhooks.md must publish the secret this test pins")
                .contains(VECTOR_SECRET)
                .as("docs/webhooks.md must publish the timestamp this test pins")
                .contains(Long.toString(VECTOR_TIMESTAMP))
                .as("docs/webhooks.md must publish the payload byte-for-byte")
                .contains(VECTOR_PAYLOAD)
                .as("docs/webhooks.md must publish the signature this test pins")
                .contains(VECTOR_SIGNATURE_HEADER);
    }

    /**
     * Walk up from the test JVM's working directory (Gradle sets it to
     * {@code core-java/}) until the repository root is found. Fails loudly rather
     * than skipping — a vector whose doc cannot be located is unverified, and
     * "could not check" must never read as "checked".
     */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("docs/webhooks.md"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "docs/webhooks.md not found above " + Paths.get("").toAbsolutePath()
                        + " — the published vector cannot be checked against the document");
    }
}
