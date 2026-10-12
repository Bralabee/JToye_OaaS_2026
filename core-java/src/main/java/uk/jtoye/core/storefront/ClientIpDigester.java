package uk.jtoye.core.storefront;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.jtoye.core.security.ClientIpResolver;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The one keyed digest of a storefront customer's client address (Phase 37-28, D-27, RWO-041 / UXT-041).
 *
 * <h2>Why a digest at all, and why keyed</h2>
 *
 * D-18's third cash-order cap is "open cash orders per client address" — the axis that bounds a flood
 * which invents a fresh email and phone per order. Owner ruling D-27 chose to make that count a QUERY
 * over the order rows rather than a Redis counter, which means something about the address has to live
 * on the order. It is never the address. It is never a plain hash either: the IPv4 space is 2^32
 * values, so an unkeyed SHA-256 is reversed by enumeration in minutes and the column would simply be
 * the address under another name. HMAC-SHA256 under a key the database never sees
 * ({@code jtoye.storefront.client-ip-hmac-key}, env {@code CLIENT_IP_HMAC_KEY}) is a pseudonym only to
 * the key holder; two environments with different keys produce unrelated digests for the same client.
 *
 * <h2>One client, one digest</h2>
 *
 * The input is the address in the resolver's canonical spelling ({@link ClientIpResolver#canonicalLiteral}):
 * an IPv4-mapped IPv6 literal ({@code ::ffff:198.51.100.7}) digests as its dotted quad, and an IPv6
 * address in any spelling digests as its RFC 5952 form — the same rule the public rate limiter keys on,
 * so a client cannot hold two counts by varying what the proxy wrote. A value that is not an IP literal
 * (the resolver's {@code "unknown"} sentinel for a request with no peer address) is digested as written,
 * which keys every such request on one count, exactly as the rate limiter keys them on one bucket.
 *
 * <h2>Failing fast, and never leaking the key</h2>
 *
 * The {@code DsarCipher} precedent: the key is validated HERE, at bean construction, so core-java refuses
 * to start without a valid 64-hex-character key rather than digesting under something guessable. The
 * refusal names the property and how to generate a key, never the value — an exception message ends up
 * in logs, and a partially-correct key in a log is a partially-leaked key. This class does no logging.
 *
 * <h2>Lifetime</h2>
 *
 * A pseudonymous identifier that lives with the order falls under retention and Article 17 (D-27):
 * {@code OrderService} nulls it when the order reaches a terminal state, {@link ClientIpDigestRetentionSweep}
 * nulls any digest older than the declared window in every tenant, and {@code GdprService} nulls it with
 * the rest of the order's PII. There is no audit mirror ({@code Order.clientIpDigest} is {@code @NotAudited}).
 */
@Component
public class ClientIpDigester {

    /** The Spring property the key is read from; named in every refusal message, never its value. */
    public static final String PROPERTY = "jtoye.storefront.client-ip-hmac-key";

    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern HEX_64 = Pattern.compile("[0-9a-fA-F]{64}");

    private final SecretKeySpec key;

    /**
     * @param hexKey 64 hexadecimal characters (32 bytes). The empty inline default exists so that an
     *               absent property reaches THIS refusal, with its generation hint, rather than a
     *               generic placeholder error.
     * @throws IllegalStateException when the key is absent, blank, the wrong length or not hex;
     *                               the message names the property and never the value
     */
    public ClientIpDigester(@Value("${" + PROPERTY + ":}") String hexKey) {
        if (hexKey == null || !HEX_64.matcher(hexKey).matches()) {
            throw new IllegalStateException(PROPERTY + " must be 64 hexadecimal characters (32 bytes); "
                    + "generate one with: openssl rand -hex 32");
        }
        byte[] raw = HexFormat.of().parseHex(hexKey);
        try {
            this.key = new SecretKeySpec(raw, ALGORITHM);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /**
     * @param ip the client address as {@code ClientIpResolver.resolve} answered it (any spelling)
     * @return the lower-case hex HMAC-SHA256 of the canonical address text under the configured key
     *         (64 characters), or {@code null} when {@code ip} is null or blank — nothing is digested,
     *         so nothing is stored and the IP cap is not consulted
     */
    public String digest(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String canonical = ClientIpResolver.canonicalLiteral(ip);
        try {
            // A Mac instance is not thread-safe and this bean is shared, so one per call. getInstance
            // is a provider lookup, cheap next to the request it sits in.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("client-IP digest failed", e);
        }
    }
}
