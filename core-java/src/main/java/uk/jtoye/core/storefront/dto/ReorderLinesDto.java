package uk.jtoye.core.storefront.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * The lines of one of the signed-in customer's own past orders, for "Order again" (#845, persona close-out
 * 2026-11-04): {@code GET /public/shops/{slug}/orders/{orderNumber}/reorder}.
 *
 * <p>What is needed to refill a basket, plus (for a delivery order) the delivery address and phone the order
 * was placed with, so the next checkout can be pre-filled. That IS personal data, returned to the verified
 * owner of the order only; the response carries no email or name. The prices are what the customer PAID, so
 * the client can say "price changed" against the live menu; they are never used to charge anything (the
 * server reprices every order at checkout, D-16).
 */
@Schema(description = "The lines of one of the caller's own past orders, to refill a basket. "
        + "For a delivery order it also returns that order's delivery address and phone number (personal data), "
        + "to its verified owner only; it carries no email or name.")
public record ReorderLinesDto(
        @Schema(description = "The order these lines belong to.") String orderNumber,
        @Schema(description = "The shop the order was placed at, so the basket for that shop can be filled.") String shopSlug,
        @Schema(description = "One entry per order line.") List<Line> lines,
        @Schema(description = "Where and on which phone this order was delivered, so the next checkout can be "
                + "pre-filled on a browser that has never seen the customer. Null for a collection order, an order "
                + "with no address, and an order whose personal data has been erased. It is the caller's OWN order "
                + "returned to the caller (the token's verified email), no-store, and nothing is stored anew: it is "
                + "the column the order already holds.", nullable = true) Delivery delivery) {

    @Schema(description = "The delivery details the order was placed with.")
    public record Delivery(
            @Schema(description = "Address line 1.") String addressLine1,
            @Schema(description = "Address line 2.", nullable = true) String addressLine2,
            @Schema(description = "Town or city.") String addressCity,
            @Schema(description = "Postcode.") String addressPostcode,
            @Schema(description = "The phone number given at checkout.", nullable = true) String phone) {
    }

    @Schema(description = "One order line as it was recorded when the order was placed.")
    public record Line(
            @Schema(description = "The product, to be matched against the shop's live menu.") UUID productId,
            @Schema(description = "The product's name when the order was placed.") String productName,
            @Schema(description = "Units ordered.") int quantity,
            @Schema(description = "The unit price paid, in pennies, when the order was placed.") long unitPricePennies) {
    }
}
