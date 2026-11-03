package uk.jtoye.core.storefront.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SW-JRD-12 (swarm epic #964): the guest order-status lookup's proof of ownership, carried in the
 * request BODY so the customer's email address never appears in a URL. A query string is kept in
 * browser history, proxy and access logs, and the Referer of anything the page loads; a body is not.
 *
 * <p>The email is checked exactly as the query-string lookup checks it (same service method, same
 * guest-tracking RLS policy), so no format rule is added here: a well-formed address that matches no
 * order and a malformed one both answer the same 404.
 */
@Schema(description = "Proof of ownership for a guest order lookup: the email address the order was placed with.")
public record OrderLookupRequest(
        @NotBlank @Size(max = 255)
        @Schema(description = "The email address the order was placed with.", example = "alice@example.com")
        String email) {
}
