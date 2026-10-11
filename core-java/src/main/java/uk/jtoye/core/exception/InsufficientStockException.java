package uk.jtoye.core.exception;

/**
 * Thrown when stock cannot be decremented because insufficient inventory
 * remains (including after {@code @Retryable} exhaustion on concurrent CONFIRM
 * events). Maps to HTTP 409 Conflict via {@code GlobalExceptionHandler} per
 * RFC 9110 §15.5.10.
 *
 * <p>Part of the CQ-01 stock race fix — two concurrent CONFIRMs on the
 * last-in-stock product produce exactly one success and one of these
 * exceptions (no oversell).
 *
 * <p><b>Phase 37-16.</b> When the refusal is about ONE product, it carries that product's name and the
 * quantity still available, so the problem response can state them as members rather than only in
 * prose: the kitchen's Accept renders "Can't accept: only {available} {product} left in stock." Both
 * are {@code null} when the refusal is not about one known product (the optimistic-lock retry
 * exhaustion path), and the handler then omits them rather than inventing values.
 */
public class InsufficientStockException extends RuntimeException {
    private final String productName;
    private final Integer available;

    public InsufficientStockException(String message) {
        this(message, null, null);
    }

    public InsufficientStockException(String message, String productName, Integer available) {
        super(message);
        this.productName = productName;
        this.available = available;
    }

    /** The product that ran out, or {@code null} when the refusal names no single product. */
    public String getProductName() {
        return productName;
    }

    /** Units of that product still in stock, or {@code null} when the refusal names no single product. */
    public Integer getAvailable() {
        return available;
    }
}
