package uk.jtoye.core.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /public/shops/{slug}/orders/{orderNumber}/review} (#846, persona close-out
 * 2026-11-04): a signed-in customer's review of one of their own COMPLETED orders.
 *
 * <p>It carries the rating and the words and NOTHING that identifies anyone: the order is named by the
 * path, and the reviewer is the customer whose verified token the request carries ({@code X-Customer-Token}).
 * There is deliberately no email and no order id here, and no photo list (no upload path writes review
 * photos).
 */
@Schema(description = "A rating and an optional comment for one of the signed-in customer's own completed orders. "
        + "The reviewer and the order are identified by the customer token and the path, never by this body.")
public record OrderReviewRequest(
        @NotNull(message = "Food rating is required")
        @Min(value = 1, message = "Rating must be between 1 and 5")
        @Max(value = 5, message = "Rating must be between 1 and 5")
        @Schema(description = "Food rating, 1 to 5.", example = "5", minimum = "1", maximum = "5")
        Integer foodRating,

        @Min(value = 1, message = "Rating must be between 1 and 5")
        @Max(value = 5, message = "Rating must be between 1 and 5")
        @Schema(description = "Delivery rating, 1 to 5, for an order that was delivered.", example = "4", nullable = true,
                minimum = "1", maximum = "5")
        Integer deliveryRating,

        @Size(max = 1000, message = "Comment must be under 1000 characters")
        @Schema(description = "What the customer wants to say. Published, with first name and initial, on the shop's page.",
                nullable = true, maxLength = 1000)
        String comment) {
}
