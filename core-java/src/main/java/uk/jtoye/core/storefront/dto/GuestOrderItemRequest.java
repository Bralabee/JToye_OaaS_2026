package uk.jtoye.core.storefront.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class GuestOrderItemRequest {
    @NotNull(message = "Product ID is required")
    private UUID productId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    /**
     * The unit price the storefront SHOWED the customer for this line, in pennies (Phase 37 D-16,
     * RWO-007 / UXT-007). Advisory to the server's arithmetic — the price charged is always the
     * product's current server-side price — and used only to tell the customer when the two differ:
     * 37-33 refuses a submit whose expectation is missing (422 {@code basket-confirmation-required})
     * or stale (409 {@code basket-changed}, listing each change such as "Suya £9.00 → £14.00") so
     * the checkout re-renders and asks for one re-confirmation under the same Idempotency-Key.
     *
     * <p>{@code NON_NULL} for the same reason as {@code GuestOrderRequest.acknowledgedAllergenMask}:
     * the idempotency fingerprint ({@code IdempotencyJson}, frozen at the Boot 3.5 bytes by
     * {@code IdempotencyFingerprintGoldenTest}) serialises this DTO. Absent when null, so a request
     * without the field hashes exactly as it did before 37-32 and a key reserved by an older pod still
     * matches; present when set, so a resubmit under the same key with a different expectation is a
     * payload mismatch (422), never a replay.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Min(value = 0, message = "expectedUnitPricePennies must not be negative")
    @Schema(description = "The unit price, in pennies, the storefront showed the customer for this line when they "
            + "confirmed the basket. The server charges its own current price and uses this only to detect a "
            + "change: a missing value is refused 422 with code BASKET_CONFIRMATION_REQUIRED, and one that differs "
            + "from the current price is refused 409 with code BASKET_CHANGED listing each change.",
            minimum = "0")
    private Long expectedUnitPricePennies;

    public UUID getProductId() { return productId; }
    public void setProductId(UUID productId) { this.productId = productId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Long getExpectedUnitPricePennies() { return expectedUnitPricePennies; }
    public void setExpectedUnitPricePennies(Long expectedUnitPricePennies) {
        this.expectedUnitPricePennies = expectedUnitPricePennies;
    }
}
