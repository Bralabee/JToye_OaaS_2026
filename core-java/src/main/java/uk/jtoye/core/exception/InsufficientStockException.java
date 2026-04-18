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
 */
public class InsufficientStockException extends RuntimeException {
    public InsufficientStockException(String message) {
        super(message);
    }
}
