package uk.jtoye.core.notification.consent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * Stateless one-click unsubscribe token (COMMS-03, RESEARCH Code Example 3).
 *
 * <p>The token is {@code base64url(HMAC-SHA256(secret, tenantId|email|category))}
 * — no token table, no expiry, no prune: an unsubscribe link never "expires"
 * (PECR-friendly) and needs no storage. Because the token binds tenant + email +
 * category, it is non-transferable across recipients or categories, which
 * mitigates unsubscribe-link forgery (a competitor suppressing another vendor's
 * customer — threat T-22-02-01).
 *
 * <p>{@link #verify} recomputes the expected token and compares with
 * {@link MessageDigest#isEqual}, a constant-time comparison that does not
 * short-circuit on the first differing byte (timing-attack safe — ASVS V6).
 *
 * <p>The signing secret is injected via {@code @Value} with an empty env-default
 * (GLOBAL_RULE_6) so this class builds independently of the 22-01
 * {@code NotificationProperties} bean; both read the same
 * {@code notification.unsubscribe.signing-secret} key. An empty secret leaves
 * the feature inert (HMAC over an empty key throws), never a security downgrade.
 */
@Service
public class UnsubscribeTokenService {

    private final byte[] signingSecret;

    public UnsubscribeTokenService(@Value("${notification.unsubscribe.signing-secret:}") String signingSecret) {
        this.signingSecret = (signingSecret == null ? "" : signingSecret).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Mint the category-scoped unsubscribe token for {@code (tenantId, email,
     * category)}. Deterministic for the same inputs + secret.
     */
    public String tokenFor(UUID tenantId, String email, NotificationCategory category) {
        byte[] mac = hmac(payload(tenantId, email, category));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    /**
     * Constant-time verification: recompute the expected token and compare with
     * {@link MessageDigest#isEqual}. A {@code null}/mismatched/tampered token
     * returns {@code false} without leaking timing information.
     */
    public boolean verify(UUID tenantId, String email, NotificationCategory category, String token) {
        if (token == null) {
            return false;
        }
        String expected = tokenFor(tenantId, email, category);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static String payload(UUID tenantId, String email, NotificationCategory category) {
        return tenantId + "|" + email + "|" + category.name();
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // Includes an empty/absent signing secret (InvalidKeyException) — the
            // feature is not configured. Fail closed; never fall back to an
            // unsigned token.
            throw new IllegalStateException("Unsubscribe token HMAC failed (is notification.unsubscribe.signing-secret set?)", e);
        }
    }
}
