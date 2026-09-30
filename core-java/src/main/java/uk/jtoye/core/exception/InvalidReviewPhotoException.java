package uk.jtoye.core.exception;

/**
 * Thrown when a new review's {@code photoUrls} names anything other than that review's own photos
 * (issue #771): every entry must be a public storage URL whose key is
 * {@code <shop tenant>/reviews/<orderId>/<one plain name>} ({@code ReviewPhotoKeys}).
 *
 * <p>Without it a customer could attach the shop's own catalogue images, another customer's review
 * photo, or any third-party URL to a review — which the storefront renders, and which GDPR erasure
 * would then have treated as the customer's photos to delete. The message names the offending index
 * and the required shape, never the submitted URL.
 *
 * <p>Maps to HTTP 400 {@code https://jtoye.uk/errors/invalid-review-photo}, beside the sibling
 * review refusals (which are 400 {@code invalid-argument}).
 */
public class InvalidReviewPhotoException extends RuntimeException {
    public InvalidReviewPhotoException(String message) {
        super(message);
    }
}
