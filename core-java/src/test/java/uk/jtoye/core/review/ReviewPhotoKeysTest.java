package uk.jtoye.core.review;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #771: the one rule deciding which object keys are a review's own photos. Creation refuses
 * anything else, and erasure never deletes anything else, so every reject case below is a key a
 * customer could otherwise have named to have erasure delete it.
 */
class ReviewPhotoKeysTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();

    private String prefix() {
        return tenant + "/reviews/" + order + "/";
    }

    @Test
    void prefixIsTenantReviewsOrder() {
        assertThat(ReviewPhotoKeys.prefix(tenant, order)).isEqualTo(prefix());
    }

    @Test
    void acceptsAPlainNameUnderTheReviewsOwnOrderPath() {
        assertThat(ReviewPhotoKeys.isReviewPhotoKey(prefix() + "p.webp", tenant, order)).as("p.webp").isTrue();
        assertThat(ReviewPhotoKeys.isReviewPhotoKey(prefix() + UUID.randomUUID() + ".webp", tenant, order))
                .as("uuid.webp").isTrue();
        assertThat(ReviewPhotoKeys.isReviewPhotoKey(prefix() + "a_b-c.1.webp", tenant, order))
                .as("a_b-c.1.webp").isTrue();
        assertThat(ReviewPhotoKeys.isReviewPhotoKey(prefix() + "a".repeat(128), tenant, order))
                .as("a 128-character name, the upper bound").isTrue();
    }

    @Test
    void rejectsEveryOtherShape() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("null", null);
        cases.put("empty", "");
        cases.put("another tenant's prefix", UUID.randomUUID() + "/reviews/" + order + "/p.webp");
        cases.put("another order's prefix", tenant + "/reviews/" + UUID.randomUUID() + "/p.webp");
        cases.put("product path", tenant + "/products/" + order + "/p.webp");
        cases.put("media derivative", tenant + "/media/" + UUID.randomUUID() + ".webp");
        cases.put("quarantine", tenant + "/quarantine/" + "a".repeat(64) + ".webp");
        cases.put("bare prefix", prefix());
        cases.put("nested segment", prefix() + "a/b.webp");
        cases.put("dot-dot walk", prefix() + "../x.webp");
        cases.put("dot-dot name", prefix() + "..");
        cases.put("hidden name", prefix() + ".hidden");
        cases.put("query", prefix() + "p.webp?x=1");
        cases.put("fragment", prefix() + "p.webp#f");
        cases.put("percent-encoded slash", prefix() + "p%2Fx.webp");
        cases.put("backslash", prefix() + "a\\b.webp");
        cases.put("upper-cased order uuid", tenant + "/reviews/" + order.toString().toUpperCase() + "/p.webp");
        cases.put("129-character name", prefix() + "a".repeat(129));

        cases.forEach((label, key) ->
                assertThat(ReviewPhotoKeys.isReviewPhotoKey(key, tenant, order)).as(label).isFalse());
    }

    @Test
    void rejectsWhenTenantOrOrderIsMissing() {
        String key = prefix() + "p.webp";
        assertThat(ReviewPhotoKeys.isReviewPhotoKey(key, null, order)).as("null tenant").isFalse();
        assertThat(ReviewPhotoKeys.isReviewPhotoKey(key, tenant, null)).as("null order").isFalse();
    }
}
