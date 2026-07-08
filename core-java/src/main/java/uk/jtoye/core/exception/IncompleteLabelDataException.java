package uk.jtoye.core.exception;

/**
 * Thrown when a PPDS (Natasha's Law) allergen label cannot be generated because
 * the product is missing required compliance data — the food business identity
 * (owning shop name + address), the shelf life, or the durability type.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity via {@code GlobalExceptionHandler}:
 * the request is well-formed, but the product's persisted data cannot be turned
 * into a compliant label. This deliberately replaces the previous non-compliant
 * fallback (a standalone allergen-summary block / a no-allergen placeholder), so
 * the API fails LOUDLY rather than emitting a misleading "compliant-looking" PDF.
 *
 * <p>The message NAMES every missing field so the vendor knows what to supply.
 */
public class IncompleteLabelDataException extends RuntimeException {
    public IncompleteLabelDataException(String message) {
        super(message);
    }
}
