package uk.jtoye.core.exception;

import java.util.UUID;

/**
 * Thrown when an order line names a product its shop has switched off ({@code available = false}).
 * Maps to HTTP 409 {@code errors/product-unavailable} via {@code GlobalExceptionHandler}, carrying the
 * product's id and name as members so an agent can tell "switched off" from "no such product" and
 * re-list with {@code availableOnly=true}.
 */
public class ProductUnavailableException extends RuntimeException {
    private final UUID productId;
    private final String productName;

    public ProductUnavailableException(UUID productId, String productName) {
        super("Product '" + productName + "' is switched off at this shop and cannot be ordered: " + productId);
        this.productId = productId;
        this.productName = productName;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }
}
