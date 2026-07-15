package uk.jtoye.core.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 webhook signer using the Stripe {@code t=<unixSeconds>,v1=<hex>}
 * scheme (COMMS-05, T-22-05-01; RESEARCH Code Example 2 — verified against
 * <a href="https://docs.stripe.com/webhooks/signatures">Stripe docs</a>).
 *
 * <p>The signed payload is {@code timestamp + "." + rawBody}. The caller MUST
 * sign the <em>exact</em> bytes it POSTs (serialize the envelope once) — signing
 * a re-serialized body makes the receiver's recomputed HMAC mismatch (Pitfall 6).
 *
 * <p>Header emitted: {@code X-JToye-Signature: t=<ts>,v1=<hex-hmac-sha256>}. The
 * receiver contract (documented for vendors): recompute HMAC over
 * {@code t + "." + rawBody}, constant-time compare, reject if
 * {@code |now - t| > tolerance} (config, default 300s).
 *
 * <p>JDK-native ({@code javax.crypto.Mac}); no dependency. Verification uses
 * {@link MessageDigest#isEqual} for a timing-attack-safe compare.
 */
@Component
public class WebhookSigner {

    /** HTTP header the signature is carried on. */
    public static final String SIGNATURE_HEADER = "X-JToye-Signature";

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Sign {@code rawBody} with {@code secret} at unix-second {@code unixTs},
     * returning the {@code t=<ts>,v1=<hex>} header value.
     *
     * @param rawBody the exact bytes that will be POSTed (sign-once, Pitfall 6)
     * @param secret  the per-subscription signing secret
     * @param unixTs  signing timestamp (unix seconds)
     */
    public String sign(byte[] rawBody, String secret, long unixTs) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(Long.toString(unixTs).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(rawBody);
            String hex = HexFormat.of().formatHex(mac.doFinal());
            return "t=" + unixTs + ",v1=" + hex;
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is guaranteed present on every JDK — this is unreachable
            // in practice, but never swallow a crypto misconfiguration silently.
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    /**
     * Constant-time verify: recompute the signature over {@code rawBody} with
     * {@code secret} at {@code unixTs} and compare it to {@code signatureHeader}.
     * A rotated/wrong secret or a tampered body yields a mismatch.
     */
    public boolean verify(byte[] rawBody, String secret, long unixTs, String signatureHeader) {
        if (signatureHeader == null) {
            return false;
        }
        String recomputed = sign(rawBody, secret, unixTs);
        return MessageDigest.isEqual(
                recomputed.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }
}
