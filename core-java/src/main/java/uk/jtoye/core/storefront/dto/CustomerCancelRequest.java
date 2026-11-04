package uk.jtoye.core.storefront.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * #868: a customer's request to cancel their own order. A GUEST proves ownership with the email the
 * order was placed with (the same proof as the tracking lookup); a SIGNED-IN customer proves it with
 * the verified token on {@code X-Customer-Token} and sends no email at all. When a token is present the
 * email here is ignored: the token's own address is the only one used.
 */
@Schema(description = "Proof of ownership for a customer cancel: the email the order was placed with. "
        + "Omit it when sending a verified customer token.")
public record CustomerCancelRequest(
        @Size(max = 255)
        @Schema(description = "The email address the order was placed with (guests).", example = "alice@example.com")
        String email) {
}
