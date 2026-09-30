package uk.jtoye.core.review;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The one rule deciding which object keys are a review's own photos (issue #771).
 *
 * <p><b>The rule.</b> A key is a review photo of the review for order {@code orderId} in tenant
 * {@code tenantId} if and only if it is {@code <tenantId>/reviews/<orderId>/<name>}, where
 * {@code <name>} is ONE plain path segment matching {@code [A-Za-z0-9][A-Za-z0-9._-]{0,127}}. The
 * leading alphanumeric excludes {@code .} and {@code ..}; the character class excludes {@code /},
 * {@code ?}, {@code #}, {@code %} and {@code \}, so a name can neither nest, walk out of its segment,
 * carry a query or fragment, nor hide an encoded character. The UUIDs are compared in their
 * canonical lower-case {@link UUID#toString()} form.
 *
 * <p><b>Shared by creation and erasure.</b> {@code ReviewService.createReview} refuses any
 * {@code photoUrls} entry whose key does not satisfy this rule, and {@code GdprService} only ever
 * deletes a review's photo whose key does. The erasure check does not depend on the creation check:
 * rows written before this rule existed are covered because erasure applies it again. It is an
 * ALLOW-list on purpose — a deny-list of vendor paths fails open for any path nobody listed.
 *
 * <p><b>Storage contract for any future review-photo upload path.</b> Store each photo at
 * {@code prefix(tenantId, orderId) + <random UUID>.<ext>}. Nothing writes under this prefix yet, so
 * until such a path exists no non-empty {@code photoUrls} value is accepted.
 *
 * <p><b>Why the ORDER, not only the tenant.</b> Binding the key to the tenant alone would still let
 * customer X name customer Y's review photo (same tenant, Y's order) on X's own review and have X's
 * erasure delete it. A review is unique per order ({@code reviews.order_id} is UNIQUE), so the order
 * path identifies exactly one review's photos.
 */
public final class ReviewPhotoKeys {

    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private ReviewPhotoKeys() {
    }

    /** {@code <tenantId>/reviews/<orderId>/} — the only directory a review's photos may live in. */
    public static String prefix(UUID tenantId, UUID orderId) {
        return tenantId + "/reviews/" + orderId + "/";
    }

    /**
     * Whether {@code key} is a photo of the review for {@code orderId} in {@code tenantId}: the
     * {@link #prefix} followed by exactly one plain name. Any null argument is {@code false}.
     */
    public static boolean isReviewPhotoKey(String key, UUID tenantId, UUID orderId) {
        if (key == null || tenantId == null || orderId == null) {
            return false;
        }
        String prefix = prefix(tenantId, orderId);
        if (!key.startsWith(prefix)) {
            return false;
        }
        return PLAIN_NAME.matcher(key.substring(prefix.length())).matches();
    }
}
