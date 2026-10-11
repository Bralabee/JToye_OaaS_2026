package uk.jtoye.core.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/orders/{id}/void} (Phase 37-10, D-10).
 *
 * <p>A void needs a reason: it is the record of why an order the books depend on was taken back out, so
 * a blank or missing reason is a 400. At most 500 characters, the {@code orders.void_reason} column; the
 * service trims it before storing and comparing.
 */
@Schema(description = "Why the order is being voided.")
public record VoidOrderRequest(
        @NotBlank(message = "A reason is required to void an order")
        @Size(max = 500, message = "The reason can be at most 500 characters")
        @Schema(description = "Why the order is being voided, e.g. \"Rang it up twice\". Required, at most 500 "
                + "characters. Repeating a void with the same reason returns the existing void.",
                example = "Customer cancelled at the counter", maxLength = 500)
        String reason
) {
}
