package uk.jtoye.core.storefront.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * The lines of one of the signed-in customer's own past orders, for "Order again" (#845, persona close-out
 * 2026-11-04): {@code GET /public/shops/{slug}/orders/{orderNumber}/reorder}.
 *
 * <p>Just what is needed to refill a basket, and nothing about the person: no email, name, phone or
 * address. The prices are what the customer PAID, so the client can say "price changed" against the live
 * menu; they are never used to charge anything (the server reprices every order at checkout, D-16).
 */
@Schema(description = "The lines of one of the caller's own past orders, to refill a basket. Carries no personal data.")
public record ReorderLinesDto(
        @Schema(description = "The order these lines belong to.") String orderNumber,
        @Schema(description = "The shop the order was placed at, so the basket for that shop can be filled.") String shopSlug,
        @Schema(description = "One entry per order line.") List<Line> lines) {

    @Schema(description = "One order line as it was recorded when the order was placed.")
    public record Line(
            @Schema(description = "The product, to be matched against the shop's live menu.") UUID productId,
            @Schema(description = "The product's name when the order was placed.") String productName,
            @Schema(description = "Units ordered.") int quantity,
            @Schema(description = "The unit price paid, in pennies, when the order was placed.") long unitPricePennies) {
    }
}
