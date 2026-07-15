package uk.jtoye.core.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMMS-05 (T-22-05-01) — the HMAC-SHA256 signer proof. A vendor recomputing
 * {@code HmacSHA256(secret) over (t + "." + rawBody)} must arrive at the same
 * {@code v1=} hex, and any tamper (byte-flip) or key rotation must invalidate the
 * signature. The Stripe {@code t=<unixSeconds>,v1=<hex>} scheme is the locked
 * wire contract (RESEARCH Code Example 2).
 */
class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();

    private static final String SECRET_A = "whsec_tenant_a_signing_secret";
    private static final String SECRET_B = "whsec_tenant_b_rotated_secret";
    private static final long TS = 1_752_000_000L;

    @Test
    void header_matchesStripeScheme() {
        byte[] body = "{\"id\":\"evt-1\",\"type\":\"order.ready\"}".getBytes(StandardCharsets.UTF_8);

        String header = signer.sign(body, SECRET_A, TS);

        assertThat(header)
                .as("X-JToye-Signature is t=<unixSeconds>,v1=<hex-sha256>")
                .matches("^t=\\d+,v1=[0-9a-f]{64}$")
                .startsWith("t=" + TS + ",v1=");
    }

    @Test
    void sameInputs_produceSameSignature() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.sign(body, SECRET_A, TS))
                .as("signing is deterministic for identical inputs")
                .isEqualTo(signer.sign(body, SECRET_A, TS));
    }

    @Test
    void byteFlippedBody_yieldsDifferentSignature() {
        byte[] body = "{\"amount\":1000}".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "{\"amount\":9000}".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.sign(body, SECRET_A, TS))
                .as("a tampered body must not verify against the original signature")
                .isNotEqualTo(signer.sign(tampered, SECRET_A, TS));
    }

    @Test
    void verify_acceptsMatchingSecret_rejectsRotatedSecret() {
        byte[] body = "{\"order\":\"o-1\"}".getBytes(StandardCharsets.UTF_8);
        String header = signer.sign(body, SECRET_A, TS);

        assertThat(signer.verify(body, SECRET_A, TS, header))
                .as("the signing secret verifies its own signature")
                .isTrue();
        assertThat(signer.verify(body, SECRET_B, TS, header))
                .as("a rotated secret (B) fails a signature made with the old secret (A)")
                .isFalse();
    }
}
