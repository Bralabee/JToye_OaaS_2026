package uk.jtoye.core.security.access.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import uk.jtoye.core.security.access.ShopRole;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/staff/invites} (D-07): invite {@code email} to {@code role} on
 * {@code shopId}, or on every shop of the business when {@code shopId} is null.
 *
 * <p>The address is trimmed and lower-cased by the server before it is validated and stored, so
 * {@code "New@Example.com "} and {@code "new@example.com"} are the same invitation. A GROUP_ADMIN
 * invitation must leave {@code shopId} null (the {@code grant()} rule; 400 otherwise), and a
 * {@code shopId} that is not a shop of the caller's business is a 404.
 */
public record CreateStaffInviteRequest(
        @NotBlank @Size(max = 320) String email,
        @NotNull ShopRole role,
        UUID shopId) {
}
