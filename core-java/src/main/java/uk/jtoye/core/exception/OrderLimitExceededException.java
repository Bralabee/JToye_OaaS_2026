package uk.jtoye.core.exception;

/**
 * An order is larger than the platform allows on one axis (Phase 37-26, D-20, UXT-042):
 * a line quantity, the number of lines, the number of units, or the basket total. Rendered as a
 * 422 {@code https://jtoye.uk/errors/order-limit-exceeded} with the field, the value and the limit
 * as typed properties, so a storefront can point at the line and an agent can branch without
 * parsing the sentence.
 *
 * <p>422, not 400: the body is well-formed and every field is valid on its own; the server refuses
 * the order as a whole. Thrown INSIDE the reserved idempotency work on the storefront path, so the
 * reservation rolls back with it and a corrected resubmit under the same key succeeds.
 */
public class OrderLimitExceededException extends RuntimeException {

    private final String field;
    private final long value;
    private final long limit;
    private final String productName;

    /**
     * @param field       which bound was exceeded: {@code quantity}, {@code lines}, {@code units} or {@code total}
     * @param value       the offending value
     * @param limit       the configured limit
     * @param productName the line's product for a {@code quantity} refusal; null for a basket-level one
     */
    public OrderLimitExceededException(String field, long value, long limit, String productName) {
        super(field + " is " + value + ", above the limit of " + limit + ".");
        this.field = field;
        this.value = value;
        this.limit = limit;
        this.productName = productName;
    }

    public String getField() {
        return field;
    }

    public long getValue() {
        return value;
    }

    public long getLimit() {
        return limit;
    }

    public String getProductName() {
        return productName;
    }
}
